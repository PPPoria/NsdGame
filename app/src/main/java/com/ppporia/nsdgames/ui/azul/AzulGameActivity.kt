package com.ppporia.nsdgames.ui.azul

import androidx.activity.viewModels
import com.poria.base.ui.BaseActivity
import com.ppporia.nsdgames.databinding.ActivityAzulGameBinding
import com.ppporia.nsdgames.viewmodel.azul.AzulGameVM

// 竖屏
class AzulGameActivity : BaseActivity<ActivityAzulGameBinding>() {
    private val vm by viewModels<AzulGameVM>()

    override fun onCreateBinding() = ActivityAzulGameBinding.inflate(layoutInflater)

}