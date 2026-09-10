package com.cyberbot.mobile.ui.nav

import androidx.lifecycle.ViewModel
import com.cyberbot.mobile.data.repo.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import androidx.lifecycle.viewModelScope

@HiltViewModel
class RootViewModel @Inject constructor(
    sessionRepository: SessionRepository,
) : ViewModel() {
    val session = sessionRepository.session.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = com.cyberbot.mobile.core.model.SessionSnapshot(),
    )
}
