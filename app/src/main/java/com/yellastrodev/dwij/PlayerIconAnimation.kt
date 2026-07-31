import android.graphics.drawable.Animatable
import android.widget.ImageView

fun ImageView.startPlayerGlitch() {
    post { (drawable as? Animatable)?.start() }
}

fun ImageView.stopPlayerGlitch() {
    (drawable as? Animatable)?.stop()
}
