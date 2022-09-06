package io.beatmaps.user.alerts

import kotlinx.html.js.onClickFunction
import react.RProps
import react.dom.a
import react.dom.i
import react.dom.span
import react.functionComponent

external interface AlertListItemProps : RProps {
    var active: Boolean?
    var icon: String?
    var text: String?
    var count: Int?
    var action: (() -> Unit)?
}

val alertsListItem = functionComponent<AlertListItemProps> {
    a("#", classes = "list-group-item list-group-item-action d-flex justify-content-between align-items-center" + if (it.active == true) " active" else "") {
        attrs.onClickFunction = { ev ->
            ev.preventDefault()
            it.action?.invoke()
        }
        span {
            it.icon?.let { i ->
                i("fas $i me-2") {}
            }
            it.text?.let { t ->
                +t
            }
        }
        it.count?.let { c ->
            span("badge rounded-pill " + if (it.active == true) "bg-light" else "bg-primary") {
                +"$c"
            }
        }
    }
}