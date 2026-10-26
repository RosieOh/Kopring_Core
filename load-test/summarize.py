"""k6 결과(--summary-export)와 실행 구간의 서버 지표(Prometheus)를 모아 마크다운 표로 만든다."""
import json
import sys
import urllib.parse
import urllib.request
from pathlib import Path

PROMETHEUS = "http://localhost:9090"
APP = 'application="kopring-core"'


def prom(query: str, at: int) -> float | None:
    url = f"{PROMETHEUS}/api/v1/query?" + urllib.parse.urlencode({"query": query, "time": at})
    result = json.load(urllib.request.urlopen(url))["data"]["result"]
    return float(result[0]["value"][1]) if result else None


def server_metrics(start: int, end: int) -> dict:
    # 실행 구간 + 지표 반영 여유(3초)
    window = end - start + 3
    at = end + 3
    return {
        "db_pending_max": prom(f"max_over_time(sum(hikaricp_connections_pending{{{APP}}})[{window}s:1s])", at),
        "tomcat_busy_max": prom(f"max_over_time(sum(tomcat_threads_busy_threads{{{APP}}})[{window}s:1s])", at),
        "lock_wait_p95": prom(
            f"histogram_quantile(0.95, sum by (le) (increase(lock_wait_seconds_bucket{{{APP}}}[{window}s])))", at
        ),
        "queue_waiting_max": prom(f'max_over_time(sum(coupon_queue_size{{{APP}, state="waiting"}})[{window}s:1s])', at),
    }


def metric(summary: dict, name: str, stat: str):
    return summary["metrics"].get(name, {}).get(stat)


def ms(value) -> str:
    if value is None:
        return "-"
    return f"{value / 1000:.2f}s" if value >= 1000 else f"{value:.0f}ms"


def count(summary: dict, name: str) -> int:
    return int(metric(summary, name, "count") or 0)


def num(value, fmt="{:.0f}") -> str:
    return "-" if value is None or value != value else fmt.format(value)  # NaN 은 "-"


def main(out_dir: str, quantity: str, label: str) -> None:
    out = Path(out_dir)
    runs = [line.split() for line in (out / "runs.txt").read_text().splitlines() if line.strip()]

    direct_rows, queue_rows = [], []
    for scenario, vus, start, end in runs:
        summary = json.loads((out / f"{scenario}-{vus}.json").read_text())
        server = server_metrics(int(start), int(end))
        requests = count(summary, "http_reqs")
        if scenario == "direct":
            direct_rows.append(
                f"| {vus} | {count(summary, 'result_issued')} | {count(summary, 'result_sold_out')} "
                f"| {count(summary, 'result_lock_timeout')} | {count(summary, 'result_other')} "
                f"| {ms(metric(summary, 'http_req_duration{name:issue}', 'med'))} "
                f"| {ms(metric(summary, 'http_req_duration{name:issue}', 'p(95)'))} "
                f"| {ms(metric(summary, 'http_req_duration{name:issue}', 'max'))} "
                f"| {num(server['lock_wait_p95'], '{:.2f}s')} | {num(server['db_pending_max'])} | {num(server['tomcat_busy_max'])} |"
            )
        else:
            queue_rows.append(
                f"| {vus} | {count(summary, 'result_issued')} | {count(summary, 'result_sold_out')} "
                f"| {count(summary, 'result_timeout') + count(summary, 'result_other')} "
                f"| {ms(metric(summary, 'http_req_duration{name:enqueue}', 'p(95)'))} "
                f"| {ms(metric(summary, 'time_to_result', 'med'))} | {ms(metric(summary, 'time_to_result', 'p(95)'))} "
                f"| {ms(metric(summary, 'time_to_result', 'max'))} | {requests} "
                f"| {num(server['queue_waiting_max'])} | {num(server['db_pending_max'])} | {num(server['tomcat_busy_max'])} |"
            )

    suffix = f" ({label})" if label else ""
    if direct_rows:
        print(f"### 즉시 발급{suffix} — 쿠폰 {quantity}장\n")
        print("| 동시 사용자 | 발급 | 소진 | 락 타임아웃 | 기타 실패 | 응답 중앙값 | 응답 p95 | 응답 최대 | 락 대기 p95 | DB 커넥션 대기 최대 | Tomcat 스레드 최대 |")
        print("|---|---|---|---|---|---|---|---|---|---|---|")
        print("\n".join(direct_rows) + "\n")
    if queue_rows:
        print(f"### 대기열{suffix} — 쿠폰 {quantity}장\n")
        print("| 동시 사용자 | 발급 | 소진 | 실패·시간 초과 | 등록 응답 p95 | 결과까지 중앙값 | 결과까지 p95 | 결과까지 최대 | 총 HTTP 요청 | 대기열 최대 | DB 커넥션 대기 최대 | Tomcat 스레드 최대 |")
        print("|---|---|---|---|---|---|---|---|---|---|---|---|")
        print("\n".join(queue_rows) + "\n")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else "?", sys.argv[3] if len(sys.argv) > 3 else "")
