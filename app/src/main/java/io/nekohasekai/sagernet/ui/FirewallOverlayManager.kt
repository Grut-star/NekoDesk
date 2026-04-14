package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import io.nekohasekai.sagernet.R
import java.lang.ref.WeakReference
import android.view.MotionEvent
import kotlin.math.abs

object FirewallOverlayManager {

    private var windowManager: WindowManager? = null
    private var overlayViewRef: WeakReference<View>? = null

    /**
     * Показывает всплывающее окно брандмауэра
     */
    fun showPopup(
        context: Context,
        uid: Int,
        appName: String,
        domain: String,
        onAllowOnce: () -> Unit,
        onDenyOnce: () -> Unit,
        onCreateRule: (Int) -> Unit // 1=AllowDomain, 2=DenyDomain, 3=AllowApp, 4=DenyApp
    ) {
        // Проверка версии Android перед вызовом canDrawOverlays
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) return
        // Защита: если прав нет, просто тихо выходим (чтобы не уронить приложение)
        if (!Settings.canDrawOverlays(context)) return

        // Если окно уже висит — убираем старое
        hidePopup()

        // Используем applicationContext, чтобы не вызвать утечку Service или Activity
        val appContext = context.applicationContext
        windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // Поскольку мы рисуем из Service (где нет UI-темы), нужно обернуть контекст,
        // чтобы Material-компоненты (наши кнопки) не крашнулись
        val themeContext = ContextThemeWrapper(appContext, R.style.Theme_SagerNet_Dialog) // Используем тему вашего приложения
        val inflater = LayoutInflater.from(themeContext)

        @SuppressLint("InflateParams")
        // Создаем локальную переменную View
        val overlayView = inflater.inflate(R.layout.layout_firewall_popup, null)

        // Сохраняем её в слабую ссылку
        overlayViewRef = WeakReference(overlayView)

        // Настраиваем параметры окна
        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            // Для новых Android используем APPLICATION_OVERLAY
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            // Флаги: не блокируем весь экран, разрешаем тыкать мимо окна
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        }

        // --- БИНДИНГ ВЬЮШЕК ---
        val tvAppName = overlayView?.findViewById<TextView>(R.id.popup_app_name)
        val tvDomain = overlayView?.findViewById<TextView>(R.id.popup_domain)
        val iconApp = overlayView?.findViewById<ImageView>(R.id.popup_app_icon)

        val containerStep1 = overlayView?.findViewById<View>(R.id.container_step_1)
        val containerStep2 = overlayView?.findViewById<View>(R.id.container_step_2)


        val btnAllowOnce = overlayView?.findViewById<Button>(R.id.btn_allow_once)
        val btnDenyOnce = overlayView?.findViewById<Button>(R.id.btn_deny_once)
        val btnCreateRule = overlayView?.findViewById<Button>(R.id.btn_create_rule)
        val btnBack = overlayView?.findViewById<Button>(R.id.btn_back_to_step_1)

        // --- ДОСТАЕМ ИМЯ И ИКОНКУ ПО UID ---
        var appNameL = "$appName ($uid)"
        if (uid < 10000) {
            appNameL = "Система Android (UID: $uid)"
            // Оставляем дефолтную иконку (обычно это зеленый андроид или иконка самого приложения)
        } else {
            val pm = appContext.packageManager
            val packages = pm.getPackagesForUid(uid)
            if (!packages.isNullOrEmpty()) {
                try {
                    val appInfo = pm.getApplicationInfo(packages[0], 0)
                    appNameL = pm.getApplicationLabel(appInfo).toString()
                    // Получаем иконку из манифеста приложения
                    val iconDrawable = pm.getApplicationIcon(appInfo)
                    iconApp?.setImageDrawable(iconDrawable)
                } catch (e: Exception) {
                    // Игнорируем: останется иконка-заглушка из XML
                }
            }
        }

        // Заполняем данные
        tvAppName?.text = appNameL
        tvDomain?.text = domain


        // iconApp.setImageDrawable(...) // Иконку сделаем позже, когда будем вытаскивать её по UID

        // --- ЛОГИКА КНОПОК ---
        overlayView?.findViewById<Button>(R.id.btn_allow_once)?.setOnClickListener {
            onAllowOnce.invoke()
            hidePopup()
        }

        overlayView?.findViewById<Button>(R.id.btn_deny_once)?.setOnClickListener {
            onDenyOnce.invoke()
            hidePopup()
        }

        // Переход ШАГ 1 -> ШАГ 2
        overlayView?.findViewById<Button>(R.id.btn_create_rule)?.setOnClickListener {
            containerStep1?.visibility = View.GONE
            containerStep2?.visibility = View.VISIBLE
        }

        // Переход ШАГ 2 -> ШАГ 1
        overlayView?.findViewById<Button>(R.id.btn_back_to_step_1)?.setOnClickListener {
            containerStep2?.visibility = View.GONE
            containerStep1?.visibility = View.VISIBLE
        }


        // --- ЛОГИКА КНОПОК ШАГ 2 (ПРАВИЛА) ---
        overlayView?.findViewById<Button>(R.id.btn_rule_allow_domain)?.setOnClickListener {
            onCreateRule.invoke(1)
            hidePopup()
        }
        overlayView?.findViewById<Button>(R.id.btn_rule_deny_domain)?.setOnClickListener {
            onCreateRule.invoke(2)
            hidePopup()
        }
        overlayView?.findViewById<Button>(R.id.btn_rule_allow_all)?.setOnClickListener {
            onCreateRule.invoke(3)
            hidePopup()
        }
        overlayView?.findViewById<Button>(R.id.btn_rule_deny_all)?.setOnClickListener {
            onCreateRule.invoke(4)
            hidePopup()
        }

        // --- СВАЙП ДЛЯ ЗАКРЫТИЯ (SWIPE TO DISMISS = Запретить) ---
        overlayView?.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0f
            private var initialTranslationX = 0f
            private val SWIPE_THRESHOLD = 250f // Чуть увеличили порог для защиты от случайных смахиваний

            @SuppressLint("ClickableViewAccessibility")
            override fun onTouch(v: View?, event: MotionEvent?): Boolean {
                if (v == null || event == null) return false

                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        // Фиксируем начальную точку касания (используем rawX для избежания дерганий)
                        startX = event.rawX
                        initialTranslationX = v.translationX
                        // Останавливаем анимацию возврата, если юзер "поймал" окно на лету
                        v.animate().cancel()
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        // Вычисляем, насколько сдвинули палец
                        val deltaX = event.rawX - startX

                        // Двигаем окно за пальцем
                        v.translationX = initialTranslationX + deltaX

                        // Красивая фича: чем дальше тянем, тем прозрачнее становится окно
                        val alphaRatio = 1f - (abs(deltaX) / (v.width.toFloat() * 0.8f))
                        v.alpha = alphaRatio.coerceIn(0.1f, 1f)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        val deltaX = event.rawX - startX

                        if (abs(deltaX) > SWIPE_THRESHOLD) {
                            // СМАХИВАНИЕ УДАЛОСЬ!
                            // Вычисляем, куда анимировать окно (за левый или правый край экрана)
                            val screenWidth = v.resources.displayMetrics.widthPixels.toFloat()
                            val endX = if (deltaX > 0) screenWidth else -screenWidth

                            // Плавно убираем окно за экран
                            v.animate()
                                .translationX(endX)
                                .alpha(0f)
                                .setDuration(200) // 200 мс на вылет
                                .withEndAction {
                                    // Только когда анимация закончилась, вызываем логику запрета и удаляем View
                                    onDenyOnce.invoke()
                                    hidePopup()
                                }
                                .start()
                        } else {
                            // НЕДОТЯНУЛИ (Случайное касание)
                            // Пружиняще возвращаем окно на место
                            v.animate()
                                .translationX(0f)
                                .alpha(1f)
                                .setDuration(200)
                                .start()
                        }
                        return true
                    }
                }
                return false
            }
        })


        // Добавляем окно на экран
        windowManager?.addView(overlayView, layoutParams)
    }



    /**
     * Скрывает и уничтожает окно
     */
    fun hidePopup() {
        try {
            overlayViewRef?.get()?.let { windowManager?.removeView(it) }
        } catch (e: Exception) {
            // Игнорируем ошибку, если вьюшка уже была удалена системой
        } finally {
            overlayViewRef?.clear()
            overlayViewRef = null
        }
    }
}