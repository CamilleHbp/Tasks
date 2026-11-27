package studio.camille.vervetwodo.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import studio.camille.vervetwodo.integration.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun agendaDateLabel(key: String, today: LocalDate, locale: Locale, text: Texts): String =
    if (key == "dayAfter") today.plusDays(2).format(DateTimeFormatter.ofPattern("EEEE", locale)) else text(key)

@Composable
internal fun WorkspaceSidebar(snapshot: WidgetSnapshot, text: Texts,
    locale: Locale, today: LocalDate, destination: String, selectedTag: String?, selectedDate: String?,
    agendaExpanded: Boolean, tagsExpanded: Boolean, modifier: Modifier = Modifier,
    onAgendaToggle: () -> Unit, onTagsToggle: () -> Unit, onDate: (String) -> Unit, onTag: (String) -> Unit,
    onViewAll: () -> Unit, onManageTags: () -> Unit) {
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SidebarHeader(text("agenda"), destination == "agenda" && selectedDate == null,
            agendaExpanded, text, "sidebar-agenda", text("sidebarViewAll"), text("sidebarViewAllDates"), onAgendaToggle, onViewAll)
        if (agendaExpanded) {
            // Keep shortcuts stable even when a date bucket is empty.
            listOf("overdue", "today", "tomorrow", "dayAfter", "thisWeek", "nextWeek", "later", "earlier", "noDate").forEach { key ->
                SidebarItem(agendaDateLabel(key, today, locale, text),
                    destination == "agenda" && selectedDate == key, "sidebar-date-$key", { onDate(key) })
            }
        }
        SidebarHeader(text("tags"), destination == "tags" && selectedTag == null,
            tagsExpanded, text, "sidebar-tags", text("sidebarManage"), text("sidebarManageTags"), onTagsToggle, onManageTags)
        if (tagsExpanded) {
            snapshot.tags.sortedBy { it.name.lowercase(locale) }.forEach { tag ->
                SidebarItem(tag.name, destination == "tags" && selectedTag == tag.id,
                    "sidebar-tag-${tag.id}", { onTag(tag.id) }, tag)
            }
            SidebarItem(text("untagged"), destination == "tags" && selectedTag == "",
                "sidebar-tag-untagged", { onTag("") })
        }
    }
}

@Composable
private fun SidebarHeader(label: String, selected: Boolean, expanded: Boolean, text: Texts,
    id: String, action: String, actionDescription: String, onToggle: () -> Unit, onNavigate: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked = maxWidth < 168.dp
        val toggle: @Composable (Modifier) -> Unit = { modifier ->
            TextButton(onClick = onToggle, modifier = modifier.testTag(id).semantics {
                stateDescription = text(if (expanded) "expanded" else "collapsed")
            }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) {
                val color = LocalContentColor.current
                Canvas(Modifier.size(16.dp)) {
                    val points = if (expanded) listOf(Offset(.25f, .375f), Offset(.5f, .625f), Offset(.75f, .375f))
                        else listOf(Offset(.375f, .25f), Offset(.625f, .5f), Offset(.375f, .75f))
                    points.zipWithNext().forEach { (a, b) ->
                        drawLine(color, Offset(a.x * size.width, a.y * size.height),
                            Offset(b.x * size.width, b.y * size.height), 1.5.dp.toPx(), StrokeCap.Round)
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(label, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
            }
        }
        val navigation: @Composable () -> Unit = {
            TextButton(onClick = onNavigate, modifier = Modifier.testTag("$id-action").semantics {
                contentDescription = actionDescription
                this.selected = selected
            }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                colors = ButtonDefaults.textButtonColors(
                    containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)) {
                Text(action, style = MaterialTheme.typography.labelMedium)
            }
        }
        if (stacked) Column {
            toggle(Modifier.fillMaxWidth())
            Box(Modifier.padding(start = 30.dp)) { navigation() }
        } else Row(verticalAlignment = Alignment.CenterVertically) {
            toggle(Modifier.weight(1f))
            navigation()
        }
    }
}

@Composable
private fun SidebarItem(label: String, selected: Boolean, id: String, onClick: () -> Unit, tag: TaskTag? = null) {
    Surface(color = if (selected) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent,
        shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().padding(start = 12.dp)) {
        Row(Modifier.testTag(id).selectable(selected, role = Role.Tab, onClick = onClick)
            .heightIn(min = 40.dp).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (tag != null) CategoryArt(tag.icon, null, Modifier.size(24.dp), tag.color)
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        }
    }
}
