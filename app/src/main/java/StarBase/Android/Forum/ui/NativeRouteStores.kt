package StarBase.Android.Forum.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/** Keep native workflows alive under search/login, releasing their bitmaps when closed. */
class NativeRouteStores : ViewModel() {
    private val owners = mutableMapOf<String, NativeRouteOwner>()
    fun owner(key: String): NativeRouteOwner = owners.getOrPut(key) { NativeRouteOwner() }
    fun remove(key: String) { owners.remove(key)?.close() }
    override fun onCleared() { owners.values.forEach { it.close() }; owners.clear() }
}

class NativeRouteOwner : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
    fun close() { viewModelStore.clear() }
}
