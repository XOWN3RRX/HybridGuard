plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    // Declaring the Kotlin plugin here also pins the Kotlin version that
    // AGP's built-in Kotlin support uses in :app.
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
