package com.ppporia.nsdgames.viewmodel.azul

import androidx.lifecycle.ViewModel
import com.ppporia.nsdgames.App


class AzulGameVM: ViewModel() {
    private val session = App.session
    private val connection = session.connection
    
}