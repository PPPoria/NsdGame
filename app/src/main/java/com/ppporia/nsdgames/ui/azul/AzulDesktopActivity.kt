package com.ppporia.nsdgames.ui.azul

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.poria.base.ui.BaseActivity
import com.ppporia.nsdgames.R
import com.ppporia.nsdgames.databinding.ActivityAzulDesktopBinding
import com.ppporia.nsdgames.viewmodel.azul.AzulDesktopVM

// 竖屏
class AzulDesktopActivity : BaseActivity<ActivityAzulDesktopBinding>() {
    private val vm by viewModels<AzulDesktopVM>()

    override fun onCreateBinding(): ActivityAzulDesktopBinding = ActivityAzulDesktopBinding.inflate(layoutInflater)

}