package ai.slock.android.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

object SlockIcons {
    val ChevronDown: ImageVector = ImageVector.Builder(
        name = "LucideChevronDown",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(6f, 9f)
            lineTo(12f, 15f)
            lineTo(18f, 9f)
        }
    }.build()

    val ChevronRight: ImageVector = strokeIcon("LucideChevronRight") {
        moveTo(9f, 18f)
        lineTo(15f, 12f)
        lineTo(9f, 6f)
    }

    val ArrowLeft: ImageVector = strokeIcon("LucideArrowLeft") {
        moveTo(12f, 19f)
        lineTo(5f, 12f)
        lineTo(12f, 5f)
        moveTo(19f, 12f)
        lineTo(5f, 12f)
    }

    val Search: ImageVector = strokeIcon("LucideSearch") {
        moveTo(21f, 21f)
        lineTo(16.66f, 16.66f)
        moveTo(19f, 11f)
        curveTo(19f, 15.4183f, 15.4183f, 19f, 11f, 19f)
        curveTo(6.5817f, 19f, 3f, 15.4183f, 3f, 11f)
        curveTo(3f, 6.5817f, 6.5817f, 3f, 11f, 3f)
        curveTo(15.4183f, 3f, 19f, 6.5817f, 19f, 11f)
        close()
    }

    val Inbox: ImageVector = strokeIcon("LucideInbox") {
        moveTo(22f, 12f)
        lineTo(16f, 12f)
        lineTo(14f, 15f)
        lineTo(10f, 15f)
        lineTo(8f, 12f)
        lineTo(2f, 12f)
        moveTo(5.45f, 5.11f)
        lineTo(2f, 12f)
        lineTo(2f, 18f)
        curveTo(2f, 19.1046f, 2.8954f, 20f, 4f, 20f)
        lineTo(20f, 20f)
        curveTo(21.1046f, 20f, 22f, 19.1046f, 22f, 18f)
        lineTo(22f, 12f)
        lineTo(18.55f, 5.11f)
        curveTo(18.213f, 4.441f, 17.529f, 4f, 16.76f, 4f)
        lineTo(7.24f, 4f)
        curveTo(6.471f, 4f, 5.787f, 4.441f, 5.45f, 5.11f)
    }

    val Bookmark: ImageVector = strokeIcon("LucideBookmark") {
        moveTo(17f, 3f)
        curveTo(18.1046f, 3f, 19f, 3.8954f, 19f, 5f)
        lineTo(19f, 20f)
        curveTo(19f, 20.3565f, 18.8101f, 20.686f, 18.5019f, 20.865f)
        curveTo(18.1937f, 21.044f, 17.8135f, 21.0452f, 17.504f, 20.868f)
        lineTo(12.992f, 18.29f)
        curveTo(12.377f, 17.939f, 11.623f, 17.939f, 11.008f, 18.29f)
        lineTo(6.496f, 20.868f)
        curveTo(6.1865f, 21.0452f, 5.8063f, 21.044f, 5.4981f, 20.865f)
        curveTo(5.1899f, 20.686f, 5f, 20.3565f, 5f, 20f)
        lineTo(5f, 5f)
        curveTo(5f, 3.8954f, 5.8954f, 3f, 7f, 3f)
        close()
    }

    val Share2: ImageVector = strokeIcon("LucideShare2") {
        moveTo(18f, 8f)
        curveTo(19.6569f, 8f, 21f, 6.6569f, 21f, 5f)
        curveTo(21f, 3.3431f, 19.6569f, 2f, 18f, 2f)
        curveTo(16.3431f, 2f, 15f, 3.3431f, 15f, 5f)
        curveTo(15f, 6.6569f, 16.3431f, 8f, 18f, 8f)
        close()
        moveTo(6f, 15f)
        curveTo(7.6569f, 15f, 9f, 13.6569f, 9f, 12f)
        curveTo(9f, 10.3431f, 7.6569f, 9f, 6f, 9f)
        curveTo(4.3431f, 9f, 3f, 10.3431f, 3f, 12f)
        curveTo(3f, 13.6569f, 4.3431f, 15f, 6f, 15f)
        close()
        moveTo(18f, 22f)
        curveTo(19.6569f, 22f, 21f, 20.6569f, 21f, 19f)
        curveTo(21f, 17.3431f, 19.6569f, 16f, 18f, 16f)
        curveTo(16.3431f, 16f, 15f, 17.3431f, 15f, 19f)
        curveTo(15f, 20.6569f, 16.3431f, 22f, 18f, 22f)
        close()
        moveTo(8.59f, 13.51f)
        lineTo(15.42f, 17.49f)
        moveTo(15.41f, 6.51f)
        lineTo(8.59f, 10.49f)
    }

    val Pencil: ImageVector = strokeIcon("LucidePencil") {
        moveTo(21.174f, 6.812f)
        curveTo(21.548f, 6.438f, 21.758f, 5.931f, 21.758f, 5.402f)
        curveTo(21.758f, 4.873f, 21.548f, 4.366f, 21.174f, 3.992f)
        lineTo(20.008f, 2.826f)
        curveTo(19.634f, 2.452f, 19.127f, 2.242f, 18.598f, 2.242f)
        curveTo(18.069f, 2.242f, 17.562f, 2.452f, 17.188f, 2.826f)
        lineTo(3f, 17.014f)
        lineTo(2f, 22f)
        lineTo(6.986f, 21f)
        lineTo(21.174f, 6.812f)
        close()
        moveTo(15f, 5f)
        lineTo(19f, 9f)
    }

    val Hash: ImageVector = strokeIcon("LucideHash") {
        moveTo(4f, 9f)
        lineTo(20f, 9f)
        moveTo(4f, 15f)
        lineTo(20f, 15f)
        moveTo(10f, 3f)
        lineTo(8f, 21f)
        moveTo(16f, 3f)
        lineTo(14f, 21f)
    }

    val MessageSquare: ImageVector = strokeIcon("LucideMessageSquare") {
        moveTo(22f, 17f)
        curveTo(22f, 18.1f, 21.1f, 19f, 20f, 19f)
        lineTo(6.8f, 19f)
        curveTo(6.3f, 19f, 5.8f, 19.2f, 5.4f, 19.6f)
        lineTo(3.2f, 21.8f)
        curveTo(2.8f, 22.2f, 2f, 21.9f, 2f, 21.3f)
        lineTo(2f, 5f)
        curveTo(2f, 3.9f, 2.9f, 3f, 4f, 3f)
        lineTo(20f, 3f)
        curveTo(21.1f, 3f, 22f, 3.9f, 22f, 5f)
        close()
    }

    val Circle: ImageVector = strokeIcon("LucideCircle") {
        moveTo(22f, 12f)
        curveTo(22f, 17.5228f, 17.5228f, 22f, 12f, 22f)
        curveTo(6.4772f, 22f, 2f, 17.5228f, 2f, 12f)
        curveTo(2f, 6.4772f, 6.4772f, 2f, 12f, 2f)
        curveTo(17.5228f, 2f, 22f, 6.4772f, 22f, 12f)
        close()
    }

    val Play: ImageVector = strokeIcon("LucidePlay") {
        moveTo(5f, 5f)
        curveTo(5f, 3.46f, 6.67f, 2.5f, 8.01f, 3.27f)
        lineTo(20.01f, 10.27f)
        curveTo(21.34f, 11.04f, 21.34f, 12.96f, 20.01f, 13.73f)
        lineTo(8f, 20.73f)
        curveTo(6.67f, 21.5f, 5f, 20.54f, 5f, 19f)
        close()
    }

    val Eye: ImageVector = strokeIcon("LucideEye") {
        moveTo(2.06f, 12.35f)
        curveTo(1.98f, 12.12f, 1.98f, 11.88f, 2.06f, 11.65f)
        curveTo(3.42f, 7.3f, 7.52f, 4f, 12f, 4f)
        curveTo(16.48f, 4f, 20.58f, 7.3f, 21.94f, 11.65f)
        curveTo(22.02f, 11.88f, 22.02f, 12.12f, 21.94f, 12.35f)
        curveTo(20.58f, 16.7f, 16.48f, 20f, 12f, 20f)
        curveTo(7.52f, 20f, 3.42f, 16.7f, 2.06f, 12.35f)
        close()
        moveTo(12f, 15f)
        curveTo(13.6569f, 15f, 15f, 13.6569f, 15f, 12f)
        curveTo(15f, 10.3431f, 13.6569f, 9f, 12f, 9f)
        curveTo(10.3431f, 9f, 9f, 10.3431f, 9f, 12f)
        curveTo(9f, 13.6569f, 10.3431f, 15f, 12f, 15f)
        close()
    }

    val CircleCheckBig: ImageVector = strokeIcon("LucideCircleCheckBig") {
        moveTo(21.8f, 10f)
        curveTo(21.9f, 10.65f, 21.9f, 11.34f, 21.8f, 12f)
        curveTo(21.8f, 17.52f, 17.52f, 22f, 12f, 22f)
        curveTo(6.48f, 22f, 2f, 17.52f, 2f, 12f)
        curveTo(2f, 6.48f, 6.48f, 2f, 12f, 2f)
        curveTo(13.85f, 2f, 15.59f, 2.5f, 17.08f, 3.38f)
        moveTo(9f, 11f)
        lineTo(12f, 14f)
        lineTo(22f, 4f)
    }

    val Ban: ImageVector = strokeIcon("LucideBan") {
        moveTo(4.93f, 4.93f)
        lineTo(19.07f, 19.07f)
        moveTo(22f, 12f)
        curveTo(22f, 17.5228f, 17.5228f, 22f, 12f, 22f)
        curveTo(6.4772f, 22f, 2f, 17.5228f, 2f, 12f)
        curveTo(2f, 6.4772f, 6.4772f, 2f, 12f, 2f)
        curveTo(17.5228f, 2f, 22f, 6.4772f, 22f, 12f)
        close()
    }

    val ListTodo: ImageVector = strokeIcon("LucideListTodo") {
        moveTo(13f, 5f)
        lineTo(21f, 5f)
        moveTo(13f, 12f)
        lineTo(21f, 12f)
        moveTo(13f, 19f)
        lineTo(21f, 19f)
        moveTo(3f, 5f)
        lineTo(4f, 6f)
        lineTo(7f, 3f)
        moveTo(3f, 12f)
        lineTo(4f, 13f)
        lineTo(7f, 10f)
        moveTo(3f, 19f)
        lineTo(4f, 20f)
        lineTo(7f, 17f)
    }

    val Columns3: ImageVector = strokeIcon("LucideColumns3") {
        moveTo(4f, 4f)
        lineTo(20f, 4f)
        lineTo(20f, 20f)
        lineTo(4f, 20f)
        close()
        moveTo(9.3f, 4f)
        lineTo(9.3f, 20f)
        moveTo(14.7f, 4f)
        lineTo(14.7f, 20f)
    }

    val LayoutList: ImageVector = strokeIcon("LucideLayoutList") {
        moveTo(8f, 6f)
        lineTo(21f, 6f)
        moveTo(8f, 12f)
        lineTo(21f, 12f)
        moveTo(8f, 18f)
        lineTo(21f, 18f)
        moveTo(3f, 6f)
        lineTo(3.1f, 6f)
        moveTo(3f, 12f)
        lineTo(3.1f, 12f)
        moveTo(3f, 18f)
        lineTo(3.1f, 18f)
    }

    val Square: ImageVector = strokeIcon("LucideSquare") {
        moveTo(5f, 3f)
        lineTo(19f, 3f)
        curveTo(20.1f, 3f, 21f, 3.9f, 21f, 5f)
        lineTo(21f, 19f)
        curveTo(21f, 20.1f, 20.1f, 21f, 19f, 21f)
        lineTo(5f, 21f)
        curveTo(3.9f, 21f, 3f, 20.1f, 3f, 19f)
        lineTo(3f, 5f)
        curveTo(3f, 3.9f, 3.9f, 3f, 5f, 3f)
        close()
    }

    val Monitor: ImageVector = strokeIcon("LucideMonitor") {
        moveTo(4f, 3f)
        lineTo(20f, 3f)
        curveTo(21.1f, 3f, 22f, 3.9f, 22f, 5f)
        lineTo(22f, 17f)
        curveTo(22f, 18.1f, 21.1f, 19f, 20f, 19f)
        lineTo(4f, 19f)
        curveTo(2.9f, 19f, 2f, 18.1f, 2f, 17f)
        lineTo(2f, 5f)
        curveTo(2f, 3.9f, 2.9f, 3f, 4f, 3f)
        close()
        moveTo(8f, 21f)
        lineTo(16f, 21f)
        moveTo(12f, 19f)
        lineTo(12f, 21f)
    }

    val Smartphone: ImageVector = strokeIcon("LucideSmartphone") {
        moveTo(7f, 2f)
        lineTo(17f, 2f)
        curveTo(18.1046f, 2f, 19f, 2.8954f, 19f, 4f)
        lineTo(19f, 20f)
        curveTo(19f, 21.1046f, 18.1046f, 22f, 17f, 22f)
        lineTo(7f, 22f)
        curveTo(5.8954f, 22f, 5f, 21.1046f, 5f, 20f)
        lineTo(5f, 4f)
        curveTo(5f, 2.8954f, 5.8954f, 2f, 7f, 2f)
        close()
        moveTo(12f, 18f)
        lineTo(12.01f, 18f)
    }

    val Check: ImageVector = strokeIcon("LucideCheck") {
        moveTo(20f, 6f)
        lineTo(9f, 17f)
        lineTo(4f, 12f)
    }

    val X: ImageVector = strokeIcon("LucideX") {
        moveTo(18f, 6f)
        lineTo(6f, 18f)
        moveTo(6f, 6f)
        lineTo(18f, 18f)
    }

    val Settings: ImageVector = Icons.Outlined.Settings

    val LogOut: ImageVector = strokeIcon("LucideLogOut") {
        moveTo(16f, 17f)
        lineTo(21f, 12f)
        lineTo(16f, 7f)
        moveTo(21f, 12f)
        lineTo(9f, 12f)
        moveTo(9f, 21f)
        lineTo(5f, 21f)
        curveTo(3.9f, 21f, 3f, 20.1f, 3f, 19f)
        lineTo(3f, 5f)
        curveTo(3f, 3.9f, 3.9f, 3f, 5f, 3f)
        lineTo(9f, 3f)
    }

    val LogIn: ImageVector = strokeIcon("LucideLogIn") {
        moveTo(10f, 17f)
        lineTo(15f, 12f)
        lineTo(10f, 7f)
        moveTo(15f, 12f)
        lineTo(3f, 12f)
        moveTo(15f, 3f)
        lineTo(19f, 3f)
        curveTo(20.1f, 3f, 21f, 3.9f, 21f, 5f)
        lineTo(21f, 19f)
        curveTo(21f, 20.1f, 20.1f, 21f, 19f, 21f)
        lineTo(15f, 21f)
    }

    val Users: ImageVector = strokeIcon("LucideUsers") {
        moveTo(16f, 21f)
        lineTo(16f, 19f)
        curveTo(16f, 16.8f, 14.2f, 15f, 12f, 15f)
        lineTo(6f, 15f)
        curveTo(3.8f, 15f, 2f, 16.8f, 2f, 19f)
        lineTo(2f, 21f)
        moveTo(16f, 3.1f)
        curveTo(18.3f, 3.7f, 19.7f, 6.1f, 19.1f, 8.4f)
        curveTo(18.7f, 9.6f, 17.8f, 10.5f, 16.7f, 10.9f)
        moveTo(22f, 21f)
        lineTo(22f, 19f)
        curveTo(22f, 17.1f, 20.7f, 15.5f, 18.9f, 15.1f)
        moveTo(9f, 3f)
        curveTo(11.2f, 3f, 13f, 4.8f, 13f, 7f)
        curveTo(13f, 9.2f, 11.2f, 11f, 9f, 11f)
        curveTo(6.8f, 11f, 5f, 9.2f, 5f, 7f)
        curveTo(5f, 4.8f, 6.8f, 3f, 9f, 3f)
        close()
    }

    val ImagePlus: ImageVector = strokeIcon("LucideImagePlus") {
        moveTo(16f, 5f)
        lineTo(22f, 5f)
        moveTo(19f, 2f)
        lineTo(19f, 8f)
        moveTo(21f, 11.5f)
        lineTo(21f, 19f)
        curveTo(21f, 20.1f, 20.1f, 21f, 19f, 21f)
        lineTo(5f, 21f)
        curveTo(3.9f, 21f, 3f, 20.1f, 3f, 19f)
        lineTo(3f, 5f)
        curveTo(3f, 3.9f, 3.9f, 3f, 5f, 3f)
        lineTo(12.5f, 3f)
        moveTo(21f, 15f)
        lineTo(17.9f, 11.9f)
        curveTo(17.1f, 11.1f, 15.9f, 11.1f, 15.1f, 11.9f)
        lineTo(6f, 21f)
        moveTo(9f, 7f)
        curveTo(10.1f, 7f, 11f, 7.9f, 11f, 9f)
        curveTo(11f, 10.1f, 10.1f, 11f, 9f, 11f)
        curveTo(7.9f, 11f, 7f, 10.1f, 7f, 9f)
        curveTo(7f, 7.9f, 7.9f, 7f, 9f, 7f)
        close()
    }

    val Paperclip: ImageVector = strokeIcon("LucidePaperclip") {
        moveTo(16f, 6f)
        lineTo(7.6f, 14.6f)
        curveTo(6.8f, 15.4f, 6.8f, 16.6f, 7.6f, 17.4f)
        curveTo(8.4f, 18.2f, 9.6f, 18.2f, 10.4f, 17.4f)
        lineTo(18.8f, 8.8f)
        curveTo(20.4f, 7.2f, 20.4f, 4.7f, 18.8f, 3.2f)
        curveTo(17.3f, 1.6f, 14.8f, 1.6f, 13.2f, 3.2f)
        lineTo(4.8f, 11.7f)
        curveTo(2.5f, 14.1f, 2.5f, 17.9f, 4.8f, 20.2f)
        curveTo(7.2f, 22.5f, 10.9f, 22.5f, 13.3f, 20.2f)
        lineTo(21.7f, 11.7f)
    }

    val Send: ImageVector = strokeIcon("LucideSend") {
        moveTo(14.5f, 21.7f)
        curveTo(14.7f, 22.2f, 15.4f, 22.2f, 15.5f, 21.7f)
        lineTo(22f, 2.7f)
        curveTo(22.1f, 2.3f, 21.7f, 1.9f, 21.3f, 2f)
        lineTo(2.3f, 8.5f)
        curveTo(1.8f, 8.7f, 1.8f, 9.4f, 2.3f, 9.5f)
        lineTo(10.2f, 12.7f)
        curveTo(10.8f, 12.9f, 11.1f, 13.2f, 11.3f, 13.8f)
        close()
        moveTo(21.9f, 2.1f)
        lineTo(10.9f, 13.1f)
    }

    val User: ImageVector = strokeIcon("LucideUser") {
        moveTo(19f, 21f)
        lineTo(19f, 19f)
        curveTo(19f, 16.8f, 17.2f, 15f, 15f, 15f)
        lineTo(9f, 15f)
        curveTo(6.8f, 15f, 5f, 16.8f, 5f, 19f)
        lineTo(5f, 21f)
        moveTo(12f, 3f)
        curveTo(14.2f, 3f, 16f, 4.8f, 16f, 7f)
        curveTo(16f, 9.2f, 14.2f, 11f, 12f, 11f)
        curveTo(9.8f, 11f, 8f, 9.2f, 8f, 7f)
        curveTo(8f, 4.8f, 9.8f, 3f, 12f, 3f)
        close()
    }

    val Bot: ImageVector = strokeIcon("LucideBot") {
        moveTo(12f, 8f)
        lineTo(12f, 4f)
        lineTo(8f, 4f)
        moveTo(6f, 8f)
        lineTo(18f, 8f)
        curveTo(19.1f, 8f, 20f, 8.9f, 20f, 10f)
        lineTo(20f, 18f)
        curveTo(20f, 19.1f, 19.1f, 20f, 18f, 20f)
        lineTo(6f, 20f)
        curveTo(4.9f, 20f, 4f, 19.1f, 4f, 18f)
        lineTo(4f, 10f)
        curveTo(4f, 8.9f, 4.9f, 8f, 6f, 8f)
        close()
        moveTo(9f, 13f)
        lineTo(9f, 15f)
        moveTo(15f, 13f)
        lineTo(15f, 15f)
        moveTo(2f, 14f)
        lineTo(4f, 14f)
        moveTo(20f, 14f)
        lineTo(22f, 14f)
    }

    val BellRing: ImageVector = strokeIcon("LucideBellRing") {
        moveTo(10.3f, 21f)
        curveTo(10.7f, 21.6f, 11.3f, 22f, 12f, 22f)
        curveTo(12.7f, 22f, 13.3f, 21.6f, 13.7f, 21f)
        moveTo(22f, 8f)
        curveTo(22f, 5.7f, 21.2f, 3.7f, 20f, 2f)
        moveTo(4f, 2f)
        curveTo(2.8f, 3.7f, 2f, 5.7f, 2f, 8f)
        moveTo(4f, 17f)
        lineTo(20f, 17f)
        curveTo(20.8f, 17f, 21.2f, 16.1f, 20.7f, 15.4f)
        curveTo(19.4f, 14f, 18f, 12.5f, 18f, 8f)
        curveTo(18f, 4.7f, 15.3f, 2f, 12f, 2f)
        curveTo(8.7f, 2f, 6f, 4.7f, 6f, 8f)
        curveTo(6f, 12.5f, 4.6f, 14f, 3.3f, 15.4f)
        curveTo(2.8f, 16.1f, 3.2f, 17f, 4f, 17f)
        close()
    }

    val FolderOpen: ImageVector = strokeIcon("LucideFolderOpen") {
        moveTo(6f, 14f)
        lineTo(7.5f, 11.1f)
        curveTo(7.8f, 10.4f, 8.5f, 10f, 9.2f, 10f)
        lineTo(20f, 10f)
        curveTo(21.3f, 10f, 22.2f, 11.2f, 21.9f, 12.5f)
        lineTo(20.4f, 18.5f)
        curveTo(20.2f, 19.4f, 19.4f, 20f, 18.5f, 20f)
        lineTo(4f, 20f)
        curveTo(2.9f, 20f, 2f, 19.1f, 2f, 18f)
        lineTo(2f, 5f)
        curveTo(2f, 3.9f, 2.9f, 3f, 4f, 3f)
        lineTo(7.9f, 3f)
        curveTo(8.6f, 3f, 9.2f, 3.3f, 9.6f, 3.9f)
        lineTo(10.4f, 5.1f)
        curveTo(10.8f, 5.7f, 11.4f, 6f, 12.1f, 6f)
        lineTo(18f, 6f)
        curveTo(19.1f, 6f, 20f, 6.9f, 20f, 8f)
        lineTo(20f, 10f)
    }

    val FileText: ImageVector = strokeIcon("LucideFileText") {
        moveTo(14f, 2f)
        lineTo(6f, 2f)
        curveTo(4.9f, 2f, 4f, 2.9f, 4f, 4f)
        lineTo(4f, 20f)
        curveTo(4f, 21.1f, 4.9f, 22f, 6f, 22f)
        lineTo(18f, 22f)
        curveTo(19.1f, 22f, 20f, 21.1f, 20f, 20f)
        lineTo(20f, 8f)
        lineTo(14f, 2f)
        close()
        moveTo(14f, 2f)
        lineTo(14f, 8f)
        lineTo(20f, 8f)
        moveTo(16f, 13f)
        lineTo(8f, 13f)
        moveTo(16f, 17f)
        lineTo(8f, 17f)
        moveTo(10f, 9f)
        lineTo(8f, 9f)
    }

    val Image: ImageVector = strokeIcon("LucideImage") {
        moveTo(19f, 3f)
        lineTo(5f, 3f)
        curveTo(3.9f, 3f, 3f, 3.9f, 3f, 5f)
        lineTo(3f, 19f)
        curveTo(3f, 20.1f, 3.9f, 21f, 5f, 21f)
        lineTo(19f, 21f)
        curveTo(20.1f, 21f, 21f, 20.1f, 21f, 19f)
        lineTo(21f, 5f)
        curveTo(21f, 3.9f, 20.1f, 3f, 19f, 3f)
        close()
        moveTo(8.5f, 8.5f)
        lineTo(8.51f, 8.5f)
        moveTo(21f, 15f)
        lineTo(16f, 10f)
        lineTo(5f, 21f)
    }

    val RefreshCw: ImageVector = strokeIcon("LucideRefreshCw") {
        moveTo(21f, 12f)
        curveTo(21f, 16.4f, 17.4f, 20f, 13f, 20f)
        curveTo(10.8f, 20f, 8.8f, 19.1f, 7.4f, 17.6f)
        moveTo(3f, 12f)
        curveTo(3f, 7.6f, 6.6f, 4f, 11f, 4f)
        curveTo(13.2f, 4f, 15.2f, 4.9f, 16.6f, 6.4f)
        moveTo(16f, 2f)
        lineTo(16f, 7f)
        lineTo(21f, 7f)
        moveTo(8f, 22f)
        lineTo(8f, 17f)
        lineTo(3f, 17f)
    }

    val Copy: ImageVector = strokeIcon("LucideCopy") {
        roundRectPath(x = 8f, y = 8f, width = 12f, height = 12f, radius = 2f)
        moveTo(16f, 8f)
        lineTo(16f, 6f)
        curveTo(16f, 4.8954f, 15.1046f, 4f, 14f, 4f)
        lineTo(6f, 4f)
        curveTo(4.8954f, 4f, 4f, 4.8954f, 4f, 6f)
        lineTo(4f, 14f)
        curveTo(4f, 15.1046f, 4.8954f, 16f, 6f, 16f)
        lineTo(8f, 16f)
    }

    val Activity: ImageVector = strokeIcon("LucideActivity") {
        moveTo(22f, 12f)
        lineTo(19.5f, 12f)
        curveTo(18.6f, 12f, 17.9f, 12.6f, 17.6f, 13.5f)
        lineTo(15.2f, 21.8f)
        lineTo(8.8f, 2.2f)
        lineTo(6.4f, 10.5f)
        curveTo(6.1f, 11.4f, 5.4f, 12f, 4.5f, 12f)
        lineTo(2f, 12f)
    }

    val Globe: ImageVector = strokeIcon("LucideGlobe") {
        moveTo(22f, 12f)
        curveTo(22f, 17.5f, 17.5f, 22f, 12f, 22f)
        curveTo(6.5f, 22f, 2f, 17.5f, 2f, 12f)
        curveTo(2f, 6.5f, 6.5f, 2f, 12f, 2f)
        curveTo(17.5f, 2f, 22f, 6.5f, 22f, 12f)
        close()
        pathDataArcGlobe()
        moveTo(2f, 12f)
        lineTo(22f, 12f)
    }

    val Mail: ImageVector = strokeIcon("LucideMail") {
        moveTo(4f, 4f)
        lineTo(20f, 4f)
        curveTo(21.1f, 4f, 22f, 4.9f, 22f, 6f)
        lineTo(22f, 18f)
        curveTo(22f, 19.1f, 21.1f, 20f, 20f, 20f)
        lineTo(4f, 20f)
        curveTo(2.9f, 20f, 2f, 19.1f, 2f, 18f)
        lineTo(2f, 6f)
        curveTo(2f, 4.9f, 2.9f, 4f, 4f, 4f)
        close()
        moveTo(22f, 6f)
        lineTo(12f, 13f)
        lineTo(2f, 6f)
    }

    val ShieldCheck: ImageVector = strokeIcon("LucideShieldCheck") {
        moveTo(20f, 13f)
        curveTo(20f, 18f, 16.5f, 20.5f, 12f, 22f)
        curveTo(7.5f, 20.5f, 4f, 18f, 4f, 13f)
        lineTo(4f, 5f)
        lineTo(12f, 2f)
        lineTo(20f, 5f)
        close()
        moveTo(9f, 12f)
        lineTo(11f, 14f)
        lineTo(15f, 10f)
    }

    val Link: ImageVector = strokeIcon("LucideLink") {
        moveTo(10f, 13f)
        curveTo(10.9f, 14.2f, 12.6f, 14.4f, 13.8f, 13.4f)
        lineTo(16.8f, 10.4f)
        curveTo(18f, 9.2f, 18f, 7.2f, 16.8f, 6f)
        curveTo(15.6f, 4.8f, 13.6f, 4.8f, 12.4f, 6f)
        lineTo(11.5f, 6.9f)
        moveTo(14f, 11f)
        curveTo(13.1f, 9.8f, 11.4f, 9.6f, 10.2f, 10.6f)
        lineTo(7.2f, 13.6f)
        curveTo(6f, 14.8f, 6f, 16.8f, 7.2f, 18f)
        curveTo(8.4f, 19.2f, 10.4f, 19.2f, 11.6f, 18f)
        lineTo(12.5f, 17.1f)
    }

    val Key: ImageVector = strokeIcon("LucideKey") {
        moveTo(15f, 7f)
        curveTo(15f, 4.8f, 16.8f, 3f, 19f, 3f)
        curveTo(21.2f, 3f, 23f, 4.8f, 23f, 7f)
        curveTo(23f, 9.2f, 21.2f, 11f, 19f, 11f)
        curveTo(18.4f, 11f, 17.8f, 10.9f, 17.3f, 10.6f)
        lineTo(9f, 19f)
        lineTo(6f, 19f)
        lineTo(6f, 16f)
        lineTo(3f, 16f)
        lineTo(3f, 13f)
        lineTo(13.4f, 2.6f)
        moveTo(19f, 7f)
        lineTo(19.01f, 7f)
    }

    val Archive: ImageVector = strokeIcon("LucideArchive") {
        moveTo(21f, 8f)
        lineTo(21f, 19f)
        curveTo(21f, 20.1f, 20.1f, 21f, 19f, 21f)
        lineTo(5f, 21f)
        curveTo(3.9f, 21f, 3f, 20.1f, 3f, 19f)
        lineTo(3f, 8f)
        moveTo(1f, 3f)
        lineTo(23f, 3f)
        lineTo(23f, 8f)
        lineTo(1f, 8f)
        close()
        moveTo(10f, 12f)
        lineTo(14f, 12f)
    }

    val Trash: ImageVector = strokeIcon("LucideTrash") {
        moveTo(3f, 6f)
        lineTo(21f, 6f)
        moveTo(19f, 6f)
        lineTo(18f, 20f)
        curveTo(17.9f, 21.1f, 17f, 22f, 15.9f, 22f)
        lineTo(8.1f, 22f)
        curveTo(7f, 22f, 6.1f, 21.1f, 6f, 20f)
        lineTo(5f, 6f)
        moveTo(10f, 11f)
        lineTo(10f, 17f)
        moveTo(14f, 11f)
        lineTo(14f, 17f)
        moveTo(9f, 6f)
        lineTo(9f, 4f)
        curveTo(9f, 2.9f, 9.9f, 2f, 11f, 2f)
        lineTo(13f, 2f)
        curveTo(14.1f, 2f, 15f, 2.9f, 15f, 4f)
        lineTo(15f, 6f)
    }

    val Bug: ImageVector = strokeIcon("LucideBug") {
        moveTo(12f, 20f)
        lineTo(12f, 11f)
        moveTo(14f, 7f)
        curveTo(16.2f, 7f, 18f, 8.8f, 18f, 11f)
        lineTo(18f, 14f)
        curveTo(18f, 17.3f, 15.3f, 20f, 12f, 20f)
        curveTo(8.7f, 20f, 6f, 17.3f, 6f, 14f)
        lineTo(6f, 11f)
        curveTo(6f, 8.8f, 7.8f, 7f, 10f, 7f)
        close()
        moveTo(14.1f, 3.9f)
        lineTo(16f, 2f)
        moveTo(8f, 2f)
        lineTo(9.9f, 3.9f)
        moveTo(21f, 21f)
        curveTo(20.2f, 18.8f, 18.7f, 17.3f, 17.2f, 17f)
        moveTo(3f, 21f)
        curveTo(3.8f, 18.8f, 5.3f, 17.3f, 6.8f, 17f)
        moveTo(21f, 5f)
        curveTo(20.5f, 7.1f, 19.2f, 8.5f, 17.5f, 9f)
        moveTo(3f, 5f)
        curveTo(3.5f, 7.1f, 4.8f, 8.5f, 6.5f, 9f)
        moveTo(18f, 13f)
        lineTo(22f, 13f)
        moveTo(6f, 13f)
        lineTo(2f, 13f)
        moveTo(9f, 7f)
        lineTo(9f, 6f)
        curveTo(9f, 4.3f, 10.3f, 3f, 12f, 3f)
        curveTo(13.7f, 3f, 15f, 4.3f, 15f, 6f)
        lineTo(15f, 7f)
    }
}

private fun strokeIcon(
    name: String,
    block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit
): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block
        )
    }.build()

private fun androidx.compose.ui.graphics.vector.PathBuilder.pathDataArcGlobe() {
    moveTo(12f, 2f)
    curveTo(14.5f, 4.7f, 16f, 8.2f, 16f, 12f)
    curveTo(16f, 15.8f, 14.5f, 19.3f, 12f, 22f)
    moveTo(12f, 2f)
    curveTo(9.5f, 4.7f, 8f, 8.2f, 8f, 12f)
    curveTo(8f, 15.8f, 9.5f, 19.3f, 12f, 22f)
}

private fun androidx.compose.ui.graphics.vector.PathBuilder.roundRectPath(
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    radius: Float
) {
    val right = x + width
    val bottom = y + height
    moveTo(x + radius, y)
    lineTo(right - radius, y)
    curveTo(right - radius / 2f, y, right, y + radius / 2f, right, y + radius)
    lineTo(right, bottom - radius)
    curveTo(right, bottom - radius / 2f, right - radius / 2f, bottom, right - radius, bottom)
    lineTo(x + radius, bottom)
    curveTo(x + radius / 2f, bottom, x, bottom - radius / 2f, x, bottom - radius)
    lineTo(x, y + radius)
    curveTo(x, y + radius / 2f, x + radius / 2f, y, x + radius, y)
}
