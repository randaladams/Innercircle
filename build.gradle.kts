plugins {
    // AGP 9 compiles Kotlin itself ("built-in Kotlin"), so the separate Kotlin plugin is no longer needed.
    id("com.android.application") version "9.4.0" apply false
}
