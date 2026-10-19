package com.resourcetracker.di

import javax.inject.Qualifier

/** Marks the application-wide [kotlinx.coroutines.CoroutineScope] provided by Hilt. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope