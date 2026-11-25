package com.zenpeq

import android.app.Application
import com.zenpeq.data.AutoEqRepository
import com.zenpeq.data.ZenRepository

class ZenPeqApp : Application() {
    val repo: ZenRepository by lazy { ZenRepository(this) }
    val autoEq: AutoEqRepository by lazy { AutoEqRepository(this) }
}
