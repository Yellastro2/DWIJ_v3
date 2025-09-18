package com.yellastrodev.dwij.activities

import android.Manifest
import android.Manifest.permission.READ_EXTERNAL_STORAGE
import android.R.id.message
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.database.Cursor
import android.graphics.drawable.AnimationDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.view.View.OnClickListener
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import com.google.android.material.snackbar.Snackbar
import com.yellastrodev.dwij.R
import com.yellastrodev.dwij.data.repo.PlayerRepository
import com.yellastrodev.dwij.fragments.LilPlayerFrag
import com.yellastrodev.dwij.models.PlayerModel
import com.yellastrodev.dwij.yApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.logging.Logger
import kotlin.getValue


class MainActivity : AppCompatActivity() {

    companion object {
        val FIRST_TRACKLIST = "tracklist"
        val FIRST_HOME = "home"
        val FIRST_PLAYER = "player"
        val FIRST_PLLIST = "playlist"
        val FIRST_TYPES = listOf<String>(FIRST_PLAYER, FIRST_PLLIST, FIRST_TRACKLIST)

        val LOG = Logger.getLogger("MainActivity")
        val RECORD_REQUEST_CODE = 31437

    }


    lateinit var mNavController: NavController

    val playerRepo: PlayerRepository by lazy {
        (application as yApplication).playerRepo
    }

    val playerModel: PlayerModel by viewModels {
        PlayerModel.Factory(
            playerRepo = (application as yApplication).playerRepo,
            trackRepo = (application as yApplication).trackRepository,
            coverRepo = (application as yApplication).coverRepository,
            playlistRepo = (application as yApplication).playlistRepository
        )
    }

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.lay_main)
        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.fragmentContainerView) as NavHostFragment
        mNavController = navHostFragment.navController

        // Добавляем мини-плеер один раз
        supportFragmentManager.commit {
            replace(R.id.main_frag_bott, LilPlayerFrag())
        }

        // Подписка на навигацию
        mNavController.addOnDestinationChangedListener { _, destination, _ ->
            updateMiniPlayerVisibility(destination.id)
        }

        // Подписка на репо
        lifecycleScope.launchWhenStarted {
            playerRepo.currentTrack.collect {
                updateMiniPlayerVisibility(mNavController.currentDestination?.id)
            }
        }



    }

    private fun updateMiniPlayerVisibility(currentDestinationId: Int?) {
        val miniPlayerFragment = supportFragmentManager.findFragmentById(R.id.main_frag_bott)
        val shouldShow = playerRepo.currentTrack.value != null &&
                currentDestinationId != R.id.bigPlayerFrag
        miniPlayerFragment?.view?.isVisible = shouldShow
    }

}