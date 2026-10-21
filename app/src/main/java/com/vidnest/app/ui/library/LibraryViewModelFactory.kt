package com.vidnest.app.ui.library

import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.vidnest.app.ServiceLocator

object LibraryViewModelFactory {
    fun provide() = viewModelFactory {
        initializer {
            LibraryViewModel(ServiceLocator.videoRepository)
        }
    }
}
