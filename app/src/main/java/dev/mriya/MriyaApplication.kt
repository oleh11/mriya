package dev.mriya

import android.app.Application
import dev.mriya.data.PassportRepository
import dev.mriya.data.ProfileStore

class MriyaApplication : Application() {
    val repository: PassportRepository by lazy { PassportRepository(this) }
    val profileStore: ProfileStore by lazy { ProfileStore(this) }
}
