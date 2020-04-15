package pl.droidsonroids.gradle.animation

import com.android.build.gradle.BaseExtension
import com.android.build.gradle.internal.tasks.DeviceProviderInstrumentTestTask
import com.android.ddmlib.AndroidDebugBridge
import com.android.ddmlib.DdmPreferences
import com.android.ddmlib.IDevice
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.plugins.BasePlugin
import org.gradle.api.tasks.TaskProvider
import java.io.IOException
import java.util.concurrent.TimeUnit

class AnimationDisablerPlugin : Plugin<Project> {

    private val androidSerials = System.getenv("ANDROID_SERIAL")?.split(',')

    override fun apply(project: Project) {
        project.pluginManager.apply(BasePlugin::class.java)

        arrayOf("com.android.application", "com.android.library", "com.android.test").forEach {
            project.plugins.withId(it) {
                project.addAnimationTasksWithDependencies()
            }
        }
    }

    private fun Project.addAnimationTasksWithDependencies() = afterEvaluate {
        val disableAnimations = registerAnimationScaleTask(false)
        val enableAnimations = registerAnimationScaleTask(true)

        tasks.withType(DeviceProviderInstrumentTestTask::class.java).configureEach { task ->
            task.dependsOn(disableAnimations)
            task.finalizedBy(enableAnimations)
        }
    }

    private fun Project.registerAnimationScaleTask(enableAnimations: Boolean): TaskProvider<Task> {
        val taskName = "connected${if (enableAnimations) "En" else "Dis"}ableAnimations"

        return tasks.register(taskName) {
            val scale = if (enableAnimations) 1f else 0f
            AndroidDebugBridge.initIfNeeded(false)
            val android = project.extensions.getByType(BaseExtension::class.java)
            val bridge = AndroidDebugBridge.createBridge(android.adbExecutable.path, false)
            val shellOutputReceiver = ADBShellOutputReceiver(it.logger)

            it.group = "verification"
            it.description = "Sets animation scale to $scale on all connected Android devices and AVDs"
            it.doLast {
                bridge.setAnimationScale(scale, shellOutputReceiver)
            }
        }
    }

    private fun AndroidDebugBridge.setAnimationScale(value: Float, shellOutputReceiver: ADBShellOutputReceiver) {
        val settingsPrefixes = listOf("window_animation", "transition_animation", "animator_duration")
        val affectedDevices = devices.filter { androidSerials == null || it.serialNumber in androidSerials }

        affectedDevices.forEach { device ->
            settingsPrefixes.forEach { prefix ->
                try {
                    device.setScaleSetting("${prefix}_scale", value, shellOutputReceiver)
                } catch (e: Exception) {
                    throw IOException("Setting ${prefix}_scale to $value on ${device.serialNumber}", e)
                }
            }
        }
    }

    private fun IDevice.setScaleSetting(key: String, value: Float, shellOutputReceiver: ADBShellOutputReceiver) {
        executeShellCommand("settings put global $key $value", shellOutputReceiver, DdmPreferences.getTimeOut().toLong(), TimeUnit.MILLISECONDS)
    }
}