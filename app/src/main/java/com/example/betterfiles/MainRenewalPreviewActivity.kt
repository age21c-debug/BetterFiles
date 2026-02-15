package com.example.betterfiles

import android.os.Bundle
import android.view.MotionEvent
import android.widget.TextView
import android.widget.ViewFlipper
import androidx.appcompat.app.AppCompatActivity

class MainRenewalPreviewActivity : AppCompatActivity() {
    private lateinit var flipper: ViewFlipper
    private lateinit var tvOrderHint: TextView
    private var downX: Float = 0f
    private var downY: Float = 0f
    private var currentIndex: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main_renewal_preview)

        flipper = findViewById(R.id.vfPreviewOrder)
        tvOrderHint = findViewById(R.id.tvPreviewOrderHint)

        showOrder(0)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
            }
            MotionEvent.ACTION_UP -> {
                val diffX = ev.x - downX
                val diffY = ev.y - downY
                if (kotlin.math.abs(diffX) > 80 && kotlin.math.abs(diffX) > kotlin.math.abs(diffY)) {
                    if (diffX < 0) {
                        showOrder((currentIndex + 1) % flipper.childCount)
                    } else {
                        showOrder((currentIndex - 1 + flipper.childCount) % flipper.childCount)
                    }
                    return true
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun showOrder(index: Int) {
        currentIndex = index
        flipper.displayedChild = index
        tvOrderHint.text = when (index) {
            0 -> getString(R.string.preview_order_a)
            1 -> getString(R.string.preview_order_b)
            else -> getString(R.string.preview_order_c)
        }
    }
}
