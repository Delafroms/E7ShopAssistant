package com.e7.shop

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.e7.shop.data.AppConfig

/**
 * Entry splash: shows the author credit "heyyo x E7SA" with a fade+scale
 * animation, then opens MainActivity. Skippable (tap to skip) and can be
 * disabled in Settings ("Show entry animation").
 */
class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // if the player disabled the entry animation, go straight to main
        val cfg = AppConfig(this)
        if (!cfg.showSplash) {
            goMain()
            return
        }

        setContentView(R.layout.activity_splash)
        val text = findViewById<TextView>(R.id.splashText)

        // fade + scale in with a non-linear circle easing
        text.alpha = 0f
        text.scaleX = 0.7f
        text.scaleY = 0.7f
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 700
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { a ->
                val f = a.animatedValue as Float
                text.alpha = f
                text.scaleX = 0.7f + 0.3f * f
                text.scaleY = 0.7f + 0.3f * f
            }
            start()
        }

        // tap to skip
        findViewById<View>(android.R.id.content).setOnClickListener { goMain() }

        // auto-advance after the animation
        text.postDelayed({ goMain() }, 1600)
    }

    private fun goMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
