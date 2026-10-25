package pt.tiago.neonline.ui.art

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.clearAndSetSemantics
import pt.tiago.neonline.ui.theme.Amber
import pt.tiago.neonline.ui.theme.TubeBlue
import kotlin.math.*

/** Fifty perimeter tubes correspond to level offsets; the icon is decorative and never a score. */
@Composable
fun SignArt(stop: Int, litSegments: Set<Int>, enabled: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier.clearAndSetSemantics {}) {
        val unit = min(size.width/140f, size.height/90f)
        withTransform({ translate((size.width-140f*unit)/2, (size.height-90f*unit)/2); scale(unit,unit,Offset.Zero) }) {
            val dim = Color(0xff514b58)
            val neon = if (enabled) TubeBlue else dim
            fun perimeter(distance: Float): Offset {
                val d = distance % 400f
                return when { d<128 -> Offset(6+d,9f); d<200 -> Offset(134f,9+d-128); d<328 -> Offset(134-d+200,81f); else -> Offset(6f,81-d+328) }
            }
            repeat(50) { index ->
                val lit = enabled && index in litSegments
                val start = perimeter((index+.12f)*8); val end = perimeter((index+.88f)*8)
                if (lit) drawLine(Amber.copy(alpha=.16f),start,end,7f,StrokeCap.Round)
                drawLine(if(lit) Amber else dim,start,end,2f,StrokeCap.Round)
            }
            fun line(x:Float,y:Float,xx:Float,yy:Float) = drawLine(neon,Offset(x,y),Offset(xx,yy),2f,StrokeCap.Round)
            fun circle(x:Float,y:Float,r:Float) = drawCircle(neon,r,Offset(x,y),style=Stroke(2f))
            fun rect(x:Float,y:Float,w:Float,h:Float) = drawRect(neon,Offset(x,y),Size(w,h),style=Stroke(2f))
            fun path(vararg points:Pair<Float,Float>) {
                val p=Path(); points.forEachIndexed { i,point -> if(i==0)p.moveTo(point.first,point.second) else p.lineTo(point.first,point.second) }; drawPath(p,neon,style=Stroke(2f,cap=StrokeCap.Round))
            }
            when(stop) {
                1 -> { rect(50f,29f,25f,35f); rect(55f,34f,15f,11f); line(46f,66f,80f,66f); path(75f to 35f,85f to 41f,85f to 59f,91f to 59f,91f to 32f,84f to 25f) }
                2 -> { path(48f to 42f,52f to 61f,77f to 61f,82f to 42f,48f to 42f); path(82f to 44f,92f to 44f,92f to 54f,80f to 54f); line(46f,66f,88f,66f); for(x in listOf(56f,66f,76f))path(x to 34f,x-2 to 29f,x+2 to 23f) }
                3 -> { circle(68f,43f,12f); circle(85f,26f,5f); path(80f to 27f,77f to 36f,73f to 45f,60f to 43f); path(64f to 54f,64f to 69f,72f to 69f); path(73f to 54f,81f to 61f,76f to 67f); line(89f,27f,95f,29f) }
                4 -> { path(59f to 25f,66f to 25f,64f to 40f,70f to 56f,67f to 65f,56f to 65f,53f to 56f,60f to 40f,59f to 25f); line(58f,36f,66f,36f); circle(86f,58f,12f); circle(82f,55f,1f); circle(88f,53f,1f) }
                5 -> { rect(44f,25f,53f,28f); path(49f to 65f,55f to 58f,83f to 58f,89f to 65f,89f to 69f,49f to 69f,49f to 65f); circle(57f,69f,3f); circle(81f,69f,3f) }
                6 -> { rect(47f,23f,46f,46f); line(47f,34f,93f,34f); circle(70f,51f,13f); path(59f to 51f,66f to 47f,74f to 55f,81f to 51f); circle(54f,29f,1f) }
                7 -> { path(46f to 33f,58f to 33f,61f to 47f,81f to 52f,83f to 60f,46f to 60f,46f to 33f); circle(53f,66f,4f); circle(75f,66f,4f); circle(88f,28f,8f); line(80f,28f,96f,28f); line(88f,20f,88f,36f) }
                8 -> { circle(60f,35f,8f); path(56f to 42f,65f to 42f,68f to 60f,61f to 63f,56f to 42f); line(65f,62f,68f,70f); drawArc(neon,70f,270f,false,Offset(79f,22f),Size(20f,22f),style=Stroke(2f)) }
                9 -> { rect(46f,52f,48f,15f); line(60f,52f,57f,34f); circle(56f,29f,5f); circle(78f,57f,3f); circle(87f,57f,2f) }
                10 -> { circle(70f,43f,21f); repeat(8) { n -> val a=n*PI.toFloat()/4; line(70f,43f,70+21*cos(a),43+21*sin(a)) }; path(57f to 70f,70f to 43f,83f to 70f); line(53f,70f,87f,70f) }
                11 -> { val p=Path().apply { moveTo(70f,23f); cubicTo(61f,37f,45f,43f,50f,53f); cubicTo(54f,61f,65f,57f,70f,51f); cubicTo(75f,57f,86f,61f,90f,53f); cubicTo(95f,43f,79f,37f,70f,23f) }; drawPath(p,neon,style=Stroke(2f)); path(70f to 51f,65f to 67f,75f to 67f,70f to 51f) }
                12 -> { val points=(0..10).map { n -> val a=-PI.toFloat()/2+n*PI.toFloat()/5; val r=if(n%2==0)24 else 10; (70+r*cos(a)) to (45+r*sin(a)) }; path(*points.toTypedArray()) }
            }
        }
    }
}
