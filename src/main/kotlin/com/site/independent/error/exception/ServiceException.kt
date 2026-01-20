package com.site.independent.error.exception

import com.site.independent.type.ErrorCode

open class ServiceException(val errorCode: ErrorCode) : RuntimeException()