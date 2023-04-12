import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
fun BackPressHandler(onBackPressed: () -> Unit) {
    val backCallback = remember(onBackPressed) {
        object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                onBackPressed()
            }
        }
    }

    LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher?.let {
        backCallback.isEnabled = true
        backCallback.remove()
        it.addCallback(backCallback)
    }
}