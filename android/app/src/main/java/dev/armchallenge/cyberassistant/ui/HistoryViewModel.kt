package dev.armchallenge.cyberassistant.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.armchallenge.cyberassistant.data.ScanEventDao
import javax.inject.Inject
import kotlinx.coroutines.launch

@HiltViewModel
class HistoryViewModel @Inject constructor(private val dao: ScanEventDao) : ViewModel() {
    val events = dao.observeRecent()
    fun deleteAll() = viewModelScope.launch { dao.deleteAll() }
    fun delete(id: Long) = viewModelScope.launch { dao.delete(id) }
}
