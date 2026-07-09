package com.firstgoy.goykeyboard

import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.util.Log
import com.firstgoy.goykeyboard.R
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import kotlin.math.abs

class GoyInputMethodService : InputMethodService() {
    private var lastX = 0f
    private var isSpaceDragging = false
    private val spaceMoveThreshold = 20f
    
    private val keyDelete by lazy { getString(R.string.key_delete) }
    private val keyEnter by lazy { getString(R.string.key_enter) }

    enum class LayoutMode { EN, RU, GOY, EMOJI, NUM }
    private var currentMode = LayoutMode.EN
    private var previousMode = LayoutMode.EN

    private val symbols = arrayOf(".", ",", "!", "?", "-", "(", ")", ":", ";", "\"", "'", "‽")
    private var isSymbolDragging = false
    private var selectedSymbolIndex = 0

    private val handler = Handler(Looper.getMainLooper())
    private var backspaceRunnable: Runnable? = null
    private var languageLongPressRunnable: Runnable? = null
    
    private var lastLanguageClickTime: Long = 0
    private val doubleTapThreshold = 300L

    private fun handleKeyPress(text: String, deltaX: Float, ic: InputConnection, view: View) {
        val swipeThreshold = 50
        
        if (text == "ru" || text == "en" || text == "goy" || text == "abc") {
        if (currentMode == LayoutMode.EMOJI || currentMode == LayoutMode.NUM) {
            // Only revert to a main layout, not another secondary layout
            currentMode = if (previousMode == LayoutMode.EMOJI || previousMode == LayoutMode.NUM) {
                LayoutMode.EN
            } else {
                previousMode
            }
            } else if (deltaX > swipeThreshold) {
                previousMode = currentMode
                currentMode = LayoutMode.NUM
            } else {
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastLanguageClickTime < doubleTapThreshold) {
                    currentMode = LayoutMode.GOY
                    lastLanguageClickTime = 0
                } else {
                    currentMode = if (currentMode == LayoutMode.EN) LayoutMode.RU else LayoutMode.EN
                    lastLanguageClickTime = currentTime
                }
            }
            setInputView(onCreateInputView())
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            return
        }

        when (text) {
            keyDelete -> {
                if (deltaX < -swipeThreshold) {
                    deleteWord(ic)
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                } else {
                    ic.deleteSurroundingText(1, 0)
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
            }
            keyEnter -> {
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
            " " -> {
                if (!isSpaceDragging) {
                    ic.commitText(" ", 1)
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
            }
            "." -> {
                if (!isSymbolDragging) {
                    ic.commitText(".", 1)
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                } else {
                    ic.finishComposingText()
                }
            }
            else -> {
                ic.commitText(text, text.length)
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
        }
    }

    private fun startBackspaceRepeating(ic: InputConnection, view: View) {
        backspaceRunnable = object : Runnable {
            override fun run() {
                ic.deleteSurroundingText(1, 0)
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                handler.postDelayed(this, 100)
            }
        }
        handler.postDelayed(backspaceRunnable!!, 500)
    }

    private fun stopBackspaceRepeating() {
        backspaceRunnable?.let { handler.removeCallbacks(it) }
        backspaceRunnable = null
    }

    private fun moveCursor(ic: InputConnection, direction: Int) {
        val request = ExtractedTextRequest()
        val extractedText = ic.getExtractedText(request, 0)
        if (extractedText != null) {
            val newPos = (extractedText.selectionStart + direction).coerceIn(0, extractedText.text.length)
            ic.setSelection(newPos, newPos)
        } else {
            val keyCode = if (direction > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        }
    }

            private fun deleteWord(ic: InputConnection) {
                val textBefore = ic.getTextBeforeCursor(100, 0) ?: ""
                if (textBefore.isEmpty()) return
                
                var i = textBefore.length - 1
                while (i >= 0 && Character.isWhitespace(textBefore[i])) i--
                
                // Handle case where text is all whitespace
                if (i < 0) {
                    ic.deleteSurroundingText(textBefore.length, 0)
                    return
                }
                
                while (i >= 0 && !Character.isWhitespace(textBefore[i])) i--
                val charsToDelete = textBefore.length - 1 - i
                ic.deleteSurroundingText(charsToDelete, 0)
            }

    override fun onCreateInputView(): View {
        val layoutId = when (currentMode) {
            LayoutMode.EN -> R.layout.keyboard_layout
            LayoutMode.RU -> R.layout.keyboard_layout_ru
            LayoutMode.GOY -> R.layout.keyboard_layout_goy
            LayoutMode.NUM -> R.layout.keyboard_layout_num
            LayoutMode.EMOJI -> R.layout.keyboard_layout_emoji
        }
        val view = layoutInflater.inflate(layoutId, null)

        if (currentMode == LayoutMode.EMOJI || currentMode == LayoutMode.NUM) {
            val backButtonId = if (currentMode == LayoutMode.EMOJI) R.id.key_emoji_back else R.id.key_num_back
            val backButton = view.findViewById<TextView>(backButtonId)
            backButton?.text = when (previousMode) {
                LayoutMode.EN -> "en"
                LayoutMode.RU -> "ru"
                LayoutMode.GOY -> "goy"
                else -> "abc"
            }
        }
        
        setupKeys(view)
        return view
    }

    private fun setupKeys(view: View) {
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                setupKeys(view.getChildAt(i))
            }
        } else if (view is TextView) {
            view.setOnTouchListener { v, event ->
                val ic = currentInputConnection ?: run {
                    Log.w("Keyboard", "No input connection available")
                    return@setOnTouchListener false
                }
                val textView = v as TextView
                val text = textView.text.toString()

                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        v.tag = event.x
                        lastX = event.x
                        isSpaceDragging = false
                        isSymbolDragging = false
                        v.isPressed = true
                        
                        if (text == keyDelete) {
                            startBackspaceRepeating(ic, v)
                        } else if (text == "ru" || text == "en" || text == "goy" || v.id == R.id.key_emoji_back || v.id == R.id.key_num_back) {
                            // Reset previousMode to a main layout if it's currently a secondary layout
                            if (previousMode == LayoutMode.EMOJI || previousMode == LayoutMode.NUM) {
                                previousMode = LayoutMode.EN
                            }
                            
                            languageLongPressRunnable = Runnable {
                                if (currentMode != LayoutMode.EMOJI) {
                                    previousMode = currentMode
                                    currentMode = LayoutMode.EMOJI
                                    setInputView(onCreateInputView())
                                    v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                }
                            }
                            handler.postDelayed(languageLongPressRunnable!!, 600)
                        }
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (text == " ") {
                            val deltaX = event.x - lastX
                            if (abs(deltaX) > spaceMoveThreshold) {
                                isSpaceDragging = true
                                moveCursor(ic, if (deltaX > 0) 1 else -1)
                                v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                lastX = event.x
                            }
                        } else if (text == "." || isSymbolDragging) {
                            val totalDeltaX = event.x - (v.tag as Float)
                            if (abs(totalDeltaX) > 40) {
                                isSymbolDragging = true
                                val step = 40f
                                val newIndex = (abs(totalDeltaX) / step).toInt().coerceIn(0, symbols.size - 1)
                                if (newIndex != selectedSymbolIndex) {
                                    selectedSymbolIndex = newIndex
                                    v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                }
                                ic.setComposingText(symbols[selectedSymbolIndex], 1)
                            }
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        v.isPressed = false
                        stopBackspaceRepeating()
                        languageLongPressRunnable?.let { handler.removeCallbacks(it) }
                        
                        val startX = v.tag as? Float ?: event.x
                        val deltaX = event.x - startX
                        handleKeyPress(text, deltaX, ic, v)
                        if (text == "." || isSymbolDragging) {
                             textView.text = "."
                        }
                        v.performClick()
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        v.isPressed = false
                        stopBackspaceRepeating()
                        languageLongPressRunnable?.let { handler.removeCallbacks(it) }
                        if (isSymbolDragging) {
                            ic.setComposingText("", 0)
                            ic.finishComposingText()
                        }
                        true
                    }
                    else -> false
                }
            }
        }
    }
}
