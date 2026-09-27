package com.amar.moneytracker

import android.os.Bundle
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.NumberFormat
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.Executors
import androidx.compose.runtime.saveable.rememberSaveable

const val ACTION_PENDING_CHANGED = "com.amar.moneytracker.PENDING_CHANGED"

class MainActivity : ComponentActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var store: TransactionDatabase
    private var entries by mutableStateOf<List<TransactionEntry>>(emptyList())
    private var pendingImports by mutableStateOf<List<PendingImport>>(emptyList())
    private var listenerEnabled by mutableStateOf(false)
    private val pendingChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { reload() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = TransactionDatabase(applicationContext)
        reload()
        setContent {
            MoneyTrackerApp(
                entries,
                pendingImports,
                listenerEnabled,
                onSave = { entry -> worker.execute { store.save(entry); reload() } },
                onDelete = { id -> worker.execute { store.delete(id); reload() } },
                onAcceptImport = { pending -> worker.execute { store.save(TransactionEntry(title = pending.title, amountCents = pending.amountCents, type = pending.type, category = "Bank", dateMillis = pending.dateMillis)); store.updatePendingStatus(pending.id, "ACCEPTED"); reload() } },
                onIgnoreImport = { pending -> worker.execute { store.updatePendingStatus(pending.id, "IGNORED"); reload() } },
                onOpenNotificationSettings = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        listenerEnabled = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        reload()
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(this, pendingChangedReceiver, IntentFilter(ACTION_PENDING_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStop() {
        unregisterReceiver(pendingChangedReceiver)
        super.onStop()
    }

    private fun reload() = worker.execute {
        val latest = store.getAll()
        val pending = store.getPendingImports()
        runOnUiThread { entries = latest; pendingImports = pending }
    }

    override fun onDestroy() { worker.shutdown(); super.onDestroy() }
}

private enum class Tab { HOME, CHARTS, REPORTS, PROFILE }
private val formatter = NumberFormat.getNumberInstance(Locale("en", "IN")).apply { maximumFractionDigits = 2 }
private val dayFormat = DateTimeFormatter.ofPattern("d MMM", Locale.US)
private val categories = listOf("Food", "Transport", "Shopping", "Bills", "Salary", "Gift", "Health", "Other")
private fun money(value: Double) = formatter.format(value)
private fun compactMoney(value: Double): String = when {
    value >= 100000 -> "${formatter.format(value / 100000)}L"
    value >= 1000 -> "${formatter.format(value / 1000)}k"
    else -> formatter.format(value)
}
private fun toCents(value: String): Long? = runCatching {
    BigDecimal(value.replace(",", "")).setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact()
}.getOrNull()
private fun Long.localDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate()
private fun dayStart(millis: Long) = millis.localDate().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

@Composable
private fun MoneyTrackerApp(
    entries: List<TransactionEntry>, pendingImports: List<PendingImport>, listenerEnabled: Boolean,
    onSave: (TransactionEntry) -> Unit, onDelete: (Long) -> Unit,
    onAcceptImport: (PendingImport) -> Unit, onIgnoreImport: (PendingImport) -> Unit,
    onOpenNotificationSettings: () -> Unit
) {
    val primary = Color(0xFFF1F6F1)
    val muted = Color(0xFF9AA89E)
    val accent = Color(0xFF2FA66A)
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var editorEntry by remember { mutableStateOf<TransactionEntry?>(null) }
    var editorOpen by remember { mutableStateOf(false) }
    var calendarOpen by remember { mutableStateOf(false) }
    var selectedDay by rememberSaveable { mutableStateOf<Long?>(null) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var reviewOpen by remember { mutableStateOf(false) }

    MaterialTheme(colorScheme = darkColorScheme(primary = accent, onPrimary = Color.White, background = Color(0xFF0E1711), onBackground = primary, surface = Color(0xFF15231A), onSurface = primary)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                TopBar(searchOpen, search, { searchOpen = !searchOpen; if (!searchOpen) search = "" }, { search = it }, { calendarOpen = true }, menuOpen, { menuOpen = !menuOpen }, { selectedDay = null }, primary)
                Box(Modifier.weight(1f)) {
                    when (tab) {
                        Tab.HOME -> Home(entries, selectedDay, search, { editorEntry = it; editorOpen = true }, { selectedDay = null; search = "" }, muted, primary)
                        Tab.CHARTS -> Charts(entries, muted, primary, accent)
                        Tab.REPORTS -> Reports(entries, muted, primary)
                        Tab.PROFILE -> Profile(listenerEnabled, pendingImports.size, { reviewOpen = true }, onOpenNotificationSettings, muted, primary)
                    }
                }
                BottomBar(tab, { tab = it }, accent, muted) { editorEntry = null; editorOpen = true }
            }
            if (calendarOpen) MonthlyCalendar(entries, selectedDay, { calendarOpen = false }) { selectedDay = it; calendarOpen = false }
            if (editorOpen) Editor(editorEntry, { editorOpen = false }, { onSave(it); editorOpen = false }, { onDelete(it); editorOpen = false })
            if (reviewOpen) PendingImportsDialog(pendingImports, { reviewOpen = false }, onAcceptImport, onIgnoreImport)
        }
    }
}

@Composable
private fun TopBar(searchOpen: Boolean, search: String, onSearchToggle: () -> Unit, onSearch: (String) -> Unit, onCalendar: () -> Unit, menuOpen: Boolean, onMenu: () -> Unit, clearDate: () -> Unit, primary: Color) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Box {
                IconButton(onClick = onMenu) { Icon(Icons.Default.Menu, "More options", tint = primary) }
                DropdownMenu(menuOpen, onDismissRequest = onMenu) {
                    DropdownMenuItem(text = { Text("Show all dates") }, onClick = { clearDate(); onMenu() })
                    DropdownMenuItem(text = { Text("Data is stored only on this device") }, enabled = false, onClick = {})
                }
            }
            Text("Money Tracker", fontSize = 25.sp, fontWeight = FontWeight.Bold)
            Row {
                IconButton(onClick = onSearchToggle) { Icon(Icons.Default.Search, "Search entries", tint = primary) }
                IconButton(onClick = onCalendar) { Icon(Icons.Default.CalendarMonth, "Choose a date", tint = primary) }
            }
        }
        if (searchOpen) OutlinedTextField(search, onSearch, Modifier.fillMaxWidth().padding(top = 6.dp), singleLine = true, placeholder = { Text("Search title or category") }, leadingIcon = { Icon(Icons.Default.Search, null) })
    }
}

@Composable
private fun Home(entries: List<TransactionEntry>, selectedDay: Long?, search: String, onEntry: (TransactionEntry) -> Unit, clear: () -> Unit, muted: Color, primary: Color) {
    val shown = entries.filter { (selectedDay == null || dayStart(it.dateMillis) == selectedDay) && (search.isBlank() || it.title.contains(search, true) || it.category.contains(search, true)) }
    val period = selectedDay?.localDate() ?: LocalDate.now()
    val monthly = entries.filter { it.dateMillis.localDate().let { d -> d.year == period.year && d.month == period.month } }
    val income = monthly.filter { it.type == EntryType.INCOME }.sumOf { it.amount }
    val expense = monthly.filter { it.type == EntryType.EXPENSE }.sumOf { it.amount }
    Column(Modifier.fillMaxSize()) {
        Summary(period, income, expense, muted, primary)
        if (selectedDay != null || search.isNotBlank()) TextButton(clear, Modifier.padding(horizontal = 12.dp)) { Text("Clear filter") }
        if (shown.isEmpty()) Empty(primary, muted) else EntryList(shown, onEntry, muted, primary, Modifier.weight(1f))
    }
}

@Composable
private fun Summary(date: LocalDate, income: Double, expense: Double, muted: Color, primary: Color) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(82.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF203328)).padding(10.dp)) {
            Text(date.year.toString(), color = muted, fontSize = 14.sp)
            Text(date.month.getDisplayName(TextStyle.SHORT, Locale.US), color = primary, fontSize = 21.sp)
        }
        SummaryCell("Expenses", expense, muted, primary)
        SummaryCell("Income", income, muted, primary)
        SummaryCell("Balance", income - expense, muted, primary)
    }
}

@Composable
private fun RowScope.SummaryCell(label: String, value: Double, muted: Color, primary: Color) = Column(Modifier.padding(start = 12.dp).weight(1f)) {
    Text(label, color = muted, fontSize = 13.sp)
    Text(money(value), color = primary, fontSize = 17.sp, maxLines = 1)
}

@Composable
private fun Empty(primary: Color, muted: Color) = Column(Modifier.fillMaxWidth().padding(top = 70.dp), horizontalAlignment = Alignment.CenterHorizontally) {
    Icon(Icons.Default.ReceiptLong, null, tint = muted, modifier = Modifier.size(52.dp))
    Spacer(Modifier.height(14.dp))
    Text("No entries yet", color = primary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    Text("Tap + to add an income or expense.", color = muted, modifier = Modifier.padding(top = 5.dp))
}

@Composable
private fun EntryList(entries: List<TransactionEntry>, onEntry: (TransactionEntry) -> Unit, muted: Color, primary: Color, modifier: Modifier) {
    val groups = entries.groupBy { it.dateMillis.localDate() }.toSortedMap(compareByDescending { it })
    LazyColumn(modifier.fillMaxWidth()) {
        groups.forEach { (date, dayEntries) ->
            item("date-$date") { DateHeader(date, dayEntries, muted, primary) }
            items(dayEntries, key = { it.id }) { RowEntry(it, { onEntry(it) }, primary) }
        }
    }
}

@Composable
private fun DateHeader(date: LocalDate, entries: List<TransactionEntry>, muted: Color, primary: Color) {
    val income = entries.filter { it.type == EntryType.INCOME }.sumOf { it.amount }
    val expense = entries.filter { it.type == EntryType.EXPENSE }.sumOf { it.amount }
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 11.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("${date.format(dayFormat)}   ${date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US)}", color = primary, fontSize = 15.sp)
        Text(listOfNotNull(if (expense > 0) "Expenses: ${money(expense)}" else null, if (income > 0) "Income: ${money(income)}" else null).joinToString("  "), color = muted, fontSize = 13.sp)
    }
}

@Composable
private fun RowEntry(entry: TransactionEntry, onClick: () -> Unit, primary: Color) {
    val income = entry.type == EntryType.INCOME
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(Modifier.size(46.dp), shape = CircleShape, color = if (income) Color(0xFF238B58) else Color(0xFFB84D4B)) {
            Icon(if (income) Icons.Default.AddCircle else Icons.Default.RemoveCircle, entry.category, tint = Color.White, modifier = Modifier.padding(11.dp))
        }
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(entry.title, color = primary, fontSize = 18.sp)
            Text(entry.category, color = Color(0xFF9AA89E), fontSize = 13.sp)
        }
        Text("${if (income) "+" else "-"}${money(entry.amount)}", color = if (income) Color(0xFF84D5A6) else primary, fontSize = 17.sp)
    }
}

@Composable
private fun Charts(entries: List<TransactionEntry>, muted: Color, primary: Color, accent: Color) {
    val groups = entries.filter { it.type == EntryType.EXPENSE }.groupBy { it.category }.mapValues { it.value.sumOf(TransactionEntry::amount) }.toList().sortedByDescending { it.second }
    Column(Modifier.fillMaxWidth().padding(18.dp)) {
        Text("Spending by category", color = primary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("Expense distribution from your saved entries", color = muted, modifier = Modifier.padding(top = 4.dp))
        if (groups.isEmpty()) Empty(primary, muted) else groups.forEach { (category, amount) ->
            val total = groups.sumOf { it.second }.coerceAtLeast(1.0)
            if (category == groups.first().first) CategoryPie(groups, primary)
            Column(Modifier.padding(top = 16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(category, color = primary); Text(money(amount), color = primary) }
                LinearProgressIndicator(progress = { (amount / total).toFloat() }, modifier = Modifier.fillMaxWidth().padding(top = 7.dp).height(7.dp).clip(CircleShape), color = accent, trackColor = Color(0xFF26382D))
            }
        }
    }
}

@Composable
private fun CategoryPie(groups: List<Pair<String, Double>>, primary: Color) {
    val colors = listOf(Color(0xFF2FA66A), Color(0xFF69B8DB), Color(0xFFD08B58), Color(0xFFB27AD6), Color(0xFFDC6B66), Color(0xFF92B66E), Color(0xFFE0B55B), Color(0xFF7695D1))
    val total = groups.sumOf { it.second }.coerceAtLeast(1.0)
    Row(Modifier.fillMaxWidth().padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(172.dp)) {
                var start = -90f
                groups.forEachIndexed { index, (_, amount) ->
                    val sweep = (amount / total * 360).toFloat()
                    drawArc(colors[index % colors.size], start, sweep, true, size = Size(size.width, size.height))
                    start += sweep
                }
                drawCircle(Color(0xFF15231A), radius = size.minDimension * .29f)
            }
            Text(money(total), color = primary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    }
}

@Composable
private fun Reports(entries: List<TransactionEntry>, muted: Color, primary: Color) {
    val income = entries.filter { it.type == EntryType.INCOME }.sumOf { it.amount }
    val expense = entries.filter { it.type == EntryType.EXPENSE }.sumOf { it.amount }
    Column(Modifier.fillMaxWidth().padding(18.dp)) {
        Text("Reports", color = primary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        ReportLine("Total income", money(income), muted, primary)
        ReportLine("Total expenses", money(expense), muted, primary)
        ReportLine("Current balance", money(income - expense), muted, primary)
        ReportLine("Saved transactions", entries.size.toString(), muted, primary)
    }
}

@Composable
private fun ReportLine(label: String, amount: String, muted: Color, primary: Color) {
    Row(Modifier.fillMaxWidth().padding(top = 22.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text(label, color = muted, fontSize = 17.sp); Text(amount, color = primary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
    HorizontalDivider(Modifier.padding(top = 14.dp), color = Color(0xFF333333))
}

@Composable
private fun Profile(
    listenerEnabled: Boolean, pendingCount: Int, reviewImports: () -> Unit,
    openNotificationSettings: () -> Unit, muted: Color, primary: Color
) = Column(Modifier.fillMaxWidth().padding(18.dp)) {
    Text("Profile", color = primary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    Text("Personal money tracker", color = muted, modifier = Modifier.padding(top = 8.dp))
    HorizontalDivider(Modifier.padding(vertical = 22.dp), color = Color(0xFF293A30))
    Text("Your entries are stored locally on this device.", color = primary, fontSize = 16.sp)
    Text("Tap any entry on Home to edit or delete it.", color = muted, modifier = Modifier.padding(top = 8.dp))
    Spacer(Modifier.height(24.dp))
    Text("Bank notification import", color = primary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    Text(
        if (listenerEnabled) "Listening is enabled. Matching bank messages wait for your approval."
        else "Enable this only if you want matching bank notifications to appear for review.",
        color = muted, modifier = Modifier.padding(top = 6.dp)
    )
    Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onClick = openNotificationSettings) { Text(if (listenerEnabled) "Listener settings" else "Enable listener") }
        if (pendingCount > 0) Button(onClick = reviewImports) { Text("Review $pendingCount") }
    }
}

@Composable
private fun PendingImportsDialog(
    imports: List<PendingImport>, dismiss: () -> Unit,
    accept: (PendingImport) -> Unit, ignore: (PendingImport) -> Unit
) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Review bank notifications") },
        text = {
            if (imports.isEmpty()) Text("There are no pending notifications.")
            else Column {
                Text("Nothing is saved until you approve it.", color = Color(0xFF9AA89E), fontSize = 13.sp)
                imports.forEach { item ->
                    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(item.title, fontWeight = FontWeight.SemiBold)
                                Text(if (item.type == EntryType.INCOME) "Income · ${money(item.amount)}" else "Expense · ${money(item.amount)}", color = Color(0xFF9AA89E), fontSize = 13.sp)
                            }
                            Row {
                                IconButton(onClick = { accept(item) }) { Icon(Icons.Default.Check, "Approve", tint = Color(0xFF35AA6A)) }
                                IconButton(onClick = { ignore(item) }) { Icon(Icons.Default.Close, "Ignore", tint = Color(0xFFD3635E)) }
                            }
                        }
                        Text(item.rawText, color = Color(0xFF7F8D83), fontSize = 12.sp, maxLines = 2)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text("Close") } }
    )
}

@Composable
private fun BottomBar(tab: Tab, select: (Tab) -> Unit, accent: Color, muted: Color, add: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Color(0xFF15231A)).navigationBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) {
        TabItem(Icons.Default.ReceiptLong, "Home", tab == Tab.HOME, accent, muted) { select(Tab.HOME) }
        TabItem(Icons.Default.PieChart, "Charts", tab == Tab.CHARTS, accent, muted) { select(Tab.CHARTS) }
        Button(onClick = add, modifier = Modifier.size(60.dp), shape = CircleShape, colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.White), contentPadding = PaddingValues(0.dp)) { Icon(Icons.Default.Add, "Add entry", modifier = Modifier.size(33.dp)) }
        TabItem(Icons.Default.Assessment, "Reports", tab == Tab.REPORTS, accent, muted) { select(Tab.REPORTS) }
        TabItem(Icons.Default.Person, "Profile", tab == Tab.PROFILE, accent, muted) { select(Tab.PROFILE) }
    }
}

@Composable
private fun TabItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, selected: Boolean, accent: Color, muted: Color, onClick: () -> Unit) {
    val color = if (selected) accent else muted
    Column(Modifier.clickable(onClick = onClick).padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) { Icon(icon, label, tint = color, modifier = Modifier.size(25.dp)); Text(label, color = color, fontSize = 12.sp) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppDatePicker(initial: Long?, dismiss: () -> Unit, selected: (Long) -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initial)
    DatePickerDialog(onDismissRequest = dismiss, confirmButton = { TextButton(onClick = { state.selectedDateMillis?.let(selected) }, enabled = state.selectedDateMillis != null) { Text("Select") } }, dismissButton = { TextButton(dismiss) { Text("Cancel") } }) { DatePicker(state) }
}

/** A finance-focused calendar: each populated day carries its net cash-flow signal. */
@Composable
private fun MonthlyCalendar(entries: List<TransactionEntry>, selectedDay: Long?, dismiss: () -> Unit, selected: (Long) -> Unit) {
    var month by remember(selectedDay) { mutableStateOf((selectedDay?.localDate() ?: LocalDate.now()).withDayOfMonth(1)) }
    val dailyNets = remember(entries) {
        entries.groupBy { dayStart(it.dateMillis) }.mapValues { (_, values) ->
            values.sumOf { if (it.type == EntryType.INCOME) it.amount else -it.amount }
        }
    }
    AlertDialog(
        onDismissRequest = dismiss,
        title = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { month = month.minusMonths(1) }) { Icon(Icons.Default.ChevronLeft, "Previous month") }
                Text(month.month.getDisplayName(TextStyle.FULL, Locale.US) + " " + month.year, fontSize = 18.sp)
                IconButton(onClick = { month = month.plusMonths(1) }) { Icon(Icons.Default.ChevronRight, "Next month") }
            }
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth()) { listOf("S", "M", "T", "W", "T", "F", "S").forEach { label -> Text(label, modifier = Modifier.weight(1f), color = Color(0xFF9AA89E), fontSize = 12.sp) } }
                val firstOffset = month.dayOfWeek.value % 7
                val days = month.lengthOfMonth()
                repeat(6) { week ->
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        repeat(7) { dayOfWeek ->
                            val index = week * 7 + dayOfWeek
                            val day = index - firstOffset + 1
                            if (day in 1..days) {
                                val date = month.withDayOfMonth(day)
                                val millis = dayStart(date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
                                val net = dailyNets[millis] ?: 0.0
                                val netColor = if (net > 0) Color(0xFF35AA6A) else if (net < 0) Color(0xFFD3635E) else Color.Transparent
                                val border = if (millis == selectedDay && net == 0.0) MaterialTheme.colorScheme.primary else netColor
                                Column(
                                    Modifier.weight(1f).aspectRatio(.82f).padding(1.dp)
                                        .then(if (border != Color.Transparent) Modifier.border(if (millis == selectedDay) 2.dp else 1.dp, border, RoundedCornerShape(7.dp)) else Modifier)
                                        .clickable { selected(millis) },
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text(day.toString(), fontSize = 13.sp)
                                    if (net != 0.0) Text("${if (net > 0) "+" else "-"}${compactMoney(kotlin.math.abs(net))}", color = netColor, fontSize = 9.sp, maxLines = 1)
                                }
                            } else Spacer(Modifier.weight(1f).aspectRatio(.82f))
                        }
                    }
                }
                Text("Green = net income · Red = net expense", color = Color(0xFF9AA89E), fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text("Close") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Editor(entry: TransactionEntry?, dismiss: () -> Unit, save: (TransactionEntry) -> Unit, delete: (Long) -> Unit) {
    var title by remember(entry) { mutableStateOf(entry?.title.orEmpty()) }
    var amount by remember(entry) { mutableStateOf(entry?.amount?.let(::money).orEmpty()) }
    var type by remember(entry) { mutableStateOf(entry?.type ?: EntryType.EXPENSE) }
    var category by remember(entry) { mutableStateOf(entry?.category ?: "Food") }
    var date by remember(entry) { mutableStateOf(entry?.dateMillis ?: dayStart(System.currentTimeMillis())) }
    var categoryOpen by remember { mutableStateOf(false) }
    var pickerOpen by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(if (entry == null) "Add transaction" else "Edit transaction") }, text = {
        Column {
            OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("Title") }, singleLine = true)
            OutlinedTextField(amount, { amount = it }, Modifier.fillMaxWidth().padding(top = 10.dp), label = { Text("Amount") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(type == EntryType.EXPENSE, { type = EntryType.EXPENSE }, label = { Text("Expense") })
                FilterChip(type == EntryType.INCOME, { type = EntryType.INCOME }, label = { Text("Income") })
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Box {
                    OutlinedButton({ categoryOpen = true }) { Text(category); Icon(Icons.Default.ArrowDropDown, null) }
                    DropdownMenu(categoryOpen, { categoryOpen = false }) { categories.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { category = option; categoryOpen = false }) } }
                }
                OutlinedButton({ pickerOpen = true }) { Icon(Icons.Default.CalendarMonth, null); Spacer(Modifier.width(5.dp)); Text(date.localDate().format(dayFormat)) }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        }
    }, confirmButton = {
        TextButton(onClick = {
            val cents = toCents(amount)
            error = when { title.trim().isEmpty() -> "Enter a title."; cents == null || cents <= 0 -> "Enter an amount greater than zero."; else -> null }
            if (error == null) save(TransactionEntry(entry?.id ?: 0, title.trim(), cents!!, type, category, date))
        }) { Text("Save") }
    }, dismissButton = {
        Row { if (entry != null) TextButton({ delete(entry.id) }) { Text("Delete", color = MaterialTheme.colorScheme.error) }; TextButton(dismiss) { Text("Cancel") } }
    })
    if (pickerOpen) AppDatePicker(date, { pickerOpen = false }) { date = dayStart(it); pickerOpen = false }
}
