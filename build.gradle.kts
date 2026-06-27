// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.kapt) apply false
}

buildscript {
    dependencies {
        classpath("io.objectbox:objectbox-gradle-plugin:4.0.3")
    }
}
