package com.polish.twitter.hooks

import android.content.Context

abstract class BaseHook {
    abstract val name: String

    abstract fun init(classLoader: ClassLoader, context: Context)
}
