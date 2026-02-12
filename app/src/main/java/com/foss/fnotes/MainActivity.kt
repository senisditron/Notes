package com.foss.fnotes

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.room.*
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

// --- KONFIGURACJA ---
val Context.dataStore by preferencesDataStore(name = "settings")
val THEME_KEY = intPreferencesKey("theme_mode")
val BIOMETRIC_KEY = booleanPreferencesKey("biometric_lock")

// --- BAZA DANYCH ---
@Entity(tableName = "notes")
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String = "",
    val content: String,
    val isPriority: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    val titleSize: Int = 20,
    val contentSize: Int = 16
)

@Entity(
    tableName = "attachments",
    foreignKeys = [ForeignKey(entity = Note::class, parentColumns = ["id"], childColumns = ["noteId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("noteId")]
)
data class Attachment(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val noteId: Int,
    val fileUri: String
)

@Dao interface NoteDao {
    @Query("SELECT * FROM notes ORDER BY isPriority DESC, timestamp DESC")
    fun getAllNotes(): Flow<List<Note>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(note: Note): Long
    @Delete suspend fun delete(note: Note)
}

@Dao interface AttachmentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(attachment: Attachment)
    @Query("SELECT * FROM attachments")
    fun getAllAttachments(): Flow<List<Attachment>>
    @Query("SELECT * FROM attachments WHERE noteId = :noteId")
    fun getAttachmentsForNote(noteId: Int): Flow<List<Attachment>>
    @Query("DELETE FROM attachments WHERE noteId = :noteId")
    suspend fun deleteAttachmentsForNote(noteId: Int)
}

@Database(entities = [Note::class, Attachment::class], version = 10, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun attachmentDao(): AttachmentDao
}

// --- VIEWMODEL ---
class NotesViewModel(private val noteDao: NoteDao, private val attachmentDao: AttachmentDao) : ViewModel() {
    val allNotes = noteDao.getAllNotes()
    val allAttachments = attachmentDao.getAllAttachments()

    fun saveNote(t: String, c: String, p: Boolean, ts: Int, cs: Int, uris: List<String>) {
        viewModelScope.launch {
            val noteId = noteDao.insert(Note(title = t, content = c, isPriority = p, titleSize = ts, contentSize = cs))
            uris.distinct().forEach { attachmentDao.insert(Attachment(noteId = noteId.toInt(), fileUri = it)) }
        }
    }

    fun updateNote(note: Note, uris: List<String>) {
        viewModelScope.launch {
            noteDao.insert(note)
            attachmentDao.deleteAttachmentsForNote(note.id)
            uris.distinct().forEach { attachmentDao.insert(Attachment(noteId = note.id, fileUri = it)) }
        }
    }

    fun deleteNote(note: Note) = viewModelScope.launch { noteDao.delete(note) }
    fun getAttachments(noteId: Int) = attachmentDao.getAttachmentsForNote(noteId)
}

val viewModelFactory = viewModelFactory {
    initializer {
        val app = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as android.app.Application)
        val db = Room.databaseBuilder(app, AppDatabase::class.java, "notes-db").fallbackToDestructiveMigration().build()
        NotesViewModel(db.noteDao(), db.attachmentDao())
    }
}

// --- POMOCNICZE ---

fun openFile(context: Context, filePath: String) {
    try {
        val file = File(filePath)
        if (!file.exists()) {
            Toast.makeText(context, "File not found", Toast.LENGTH_SHORT).show()
            return
        }
        val contentUri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, "image/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "Cannot open: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

fun shareNote(context: Context, title: String, text: String) {
    val full = if (title.isBlank()) text else "$title\n\n$text"
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, full) }, "Share via"))
}

fun copyToClipboard(context: Context, title: String, text: String) {
    val full = if (title.isBlank()) text else "$title\n\n$text"
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("FNote", full))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}

fun showBiometricPrompt(activity: FragmentActivity, onResult: (Boolean) -> Unit) {
    val executor = ContextCompat.getMainExecutor(activity)
    val biometricPrompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { onResult(true) }
        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON) onResult(false)
        }
    })
    val promptInfo = BiometricPrompt.PromptInfo.Builder().setTitle("Security Check").setSubtitle("Authenticate to access your notes").setNegativeButtonText("Cancel").build()
    biometricPrompt.authenticate(promptInfo)
}

// --- MAIN ACTIVITY ---
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        val viewModel: NotesViewModel = ViewModelProvider(this, viewModelFactory)[NotesViewModel::class.java]

        setContent {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            var isUnlocked by remember { mutableStateOf(false) }

            val savedTheme by context.dataStore.data.map { it[THEME_KEY] ?: 0 }.collectAsState(initial = 0)
            val isBiometricEnabled by context.dataStore.data.map { it[BIOMETRIC_KEY] ?: false }.collectAsState(initial = false)

            LaunchedEffect(Unit) {
                val biometricEnabled = context.dataStore.data.map { it[BIOMETRIC_KEY] ?: false }.first()
                if (biometricEnabled) {
                    showBiometricPrompt(this@MainActivity) { success -> isUnlocked = success }
                } else {
                    isUnlocked = true
                }
            }

            val colorScheme = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && savedTheme == 0 -> { if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context) }
                savedTheme == 1 -> lightColorScheme()
                savedTheme == 2 -> darkColorScheme()
                savedTheme == 3 -> darkColorScheme().copy(surface = Color.Black, background = Color.Black)
                else -> if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            }

            MaterialTheme(colorScheme = colorScheme) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (isUnlocked) {
                        MainScreen(
                            notes = viewModel.allNotes.collectAsState(initial = emptyList()).value,
                            attachments = viewModel.allAttachments.collectAsState(initial = emptyList()).value,
                            viewModel = viewModel,
                            themeMode = savedTheme,
                            isBiometricEnabled = isBiometricEnabled,
                            onThemeChange = { n -> scope.launch { context.dataStore.edit { it[THEME_KEY] = n } } },
                            onBiometricToggle = { n -> scope.launch { context.dataStore.edit { it[BIOMETRIC_KEY] = n } } }
                        )
                    }
                }
            }
        }
    }
}

// --- EKRAN GŁÓWNY ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(notes: List<Note>, attachments: List<Attachment>, viewModel: NotesViewModel, themeMode: Int, isBiometricEnabled: Boolean, onThemeChange: (Int) -> Unit, onBiometricToggle: (Boolean) -> Unit) {
    var currentScreen by remember { mutableStateOf("list") }
    var isDialogOpen by remember { mutableStateOf(false) }
    var noteTitle by remember { mutableStateOf("") }
    var noteText by remember { mutableStateOf("") }
    var isPinned by remember { mutableStateOf(false) }
    var currentTitleSize by remember { mutableIntStateOf(20) }
    var currentContentSize by remember { mutableIntStateOf(16) }
    var selectedUris by remember { mutableStateOf<List<String>>(emptyList()) }
    var editingNote by remember { mutableStateOf<Note?>(null) }

    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val context = LocalContext.current
    val filteredNotes = notes.filter { it.content.contains(searchQuery, true) || it.title.contains(searchQuery, true) }

    val currentNoteAttachments by if (editingNote != null) {
        viewModel.getAttachments(editingNote!!.id).collectAsState(initial = emptyList())
    } else {
        remember { mutableStateOf(emptyList<Attachment>()) }
    }

    LaunchedEffect(currentNoteAttachments, isDialogOpen) {
        if (isDialogOpen && editingNote != null) {
            selectedUris = currentNoteAttachments.map { it.fileUri }
        } else if (!isDialogOpen) {
            selectedUris = emptyList()
        }
    }

    // --- FUNKCJA AUTOZAPISU ---
    val saveAction = {
        if (noteText.isNotBlank() || noteTitle.isNotBlank()) {
            val cur = editingNote
            if (cur == null) {
                viewModel.saveNote(noteTitle, noteText, isPinned, currentTitleSize, currentContentSize, selectedUris)
            } else {
                viewModel.updateNote(cur.copy(title = noteTitle, content = noteText, isPriority = isPinned, timestamp = System.currentTimeMillis(), titleSize = currentTitleSize, contentSize = currentContentSize), selectedUris)
            }
        }
        isDialogOpen = false
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        val copiedPaths = uris.mapNotNull { uri ->
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                val fileName = "img_${System.currentTimeMillis()}.jpg"
                val file = File(context.filesDir, fileName)
                inputStream?.use { input -> FileOutputStream(file).use { output -> input.copyTo(output) } }
                file.absolutePath
            } catch (e: Exception) { null }
        }
        selectedUris = selectedUris + copiedPaths
    }

    LaunchedEffect(isSearchActive) { if (isSearchActive) focusRequester.requestFocus() }

    // Obsługa przycisku wstecz (dla wyszukiwarki, ustawień i AUTOZAPISU w dialogu)
    BackHandler(enabled = currentScreen == "settings" || isSearchActive || isDialogOpen) {
        if (isDialogOpen) saveAction()
        else if (isSearchActive) { isSearchActive = false; searchQuery = "" }
        else currentScreen = "list"
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    if (isSearchActive) {
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth().height(48.dp)) {
                            Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.padding(horizontal = 16.dp)) {
                                BasicTextField(value = searchQuery, onValueChange = { searchQuery = it }, modifier = Modifier.fillMaxWidth().focusRequester(focusRequester), textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface), cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), singleLine = true, decorationBox = { inner -> if (searchQuery.isEmpty()) Text("Search...", modifier = Modifier.alpha(0.5f)); inner() })
                            }
                        }
                    } else { Text(if (currentScreen == "list") "Notes" else "Settings", fontWeight = FontWeight.Bold) }
                },
                navigationIcon = { if (currentScreen == "settings" || isSearchActive) IconButton(onClick = { if (isSearchActive) { isSearchActive = false; searchQuery = "" } else currentScreen = "list" }) { Icon(Icons.Default.ArrowBack, null) } },
                actions = { if (currentScreen == "list") { IconButton(onClick = { isSearchActive = !isSearchActive }) { Icon(if (isSearchActive) Icons.Default.Close else Icons.Default.Search, null) }; IconButton(onClick = { currentScreen = "settings" }) { Icon(Icons.Default.Settings, null) } } }
            )
        },
        floatingActionButton = {
            if (currentScreen == "list") {
                FloatingActionButton(onClick = { editingNote = null; noteTitle = ""; noteText = ""; isPinned = false; currentTitleSize = 20; currentContentSize = 16; selectedUris = emptyList(); isDialogOpen = true }, containerColor = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(22.dp), modifier = Modifier.size(72.dp)) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(30.dp))
                }
            }
        }
    ) { p ->
        Box(modifier = Modifier.padding(p)) {
            if (currentScreen == "list") {
                if (filteredNotes.isEmpty()) {
                    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Outlined.EditNote, null, modifier = Modifier.size(100.dp).alpha(0.1f)); Spacer(Modifier.height(16.dp)); Text("No notes found", modifier = Modifier.alpha(0.3f), style = MaterialTheme.typography.bodyLarge) }
                } else {
                    LazyVerticalStaggeredGrid(columns = StaggeredGridCells.Fixed(2), modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalItemSpacing = 8.dp) {
                        items(filteredNotes) { note ->
                            val count = attachments.count { it.noteId == note.id }
                            NoteCard(note, count) {
                                editingNote = note; noteTitle = note.title; noteText = note.content; isPinned = note.isPriority; currentTitleSize = note.titleSize; currentContentSize = note.contentSize; isDialogOpen = true
                            }
                        }
                    }
                }
            } else { SettingsScreen(themeMode, isBiometricEnabled, onThemeChange, onBiometricToggle) }
        }

        if (isDialogOpen) {
            AlertDialog(
                onDismissRequest = { saveAction() }, // AUTOZAPIS przy kliknięciu obok
                title = { Text(if (editingNote == null) "New note" else "Edit note", fontWeight = FontWeight.Bold) },
                text = {
                    Column {
                        OutlinedTextField(value = noteTitle, onValueChange = { noteTitle = it }, placeholder = { Text("Title") }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(12.dp), textStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = currentTitleSize.sp))
                        Spacer(Modifier.height(16.dp))
                        OutlinedTextField(value = noteText, onValueChange = { noteText = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp), placeholder = { Text("Content...") }, shape = RoundedCornerShape(12.dp), textStyle = LocalTextStyle.current.copy(fontSize = currentContentSize.sp))

                        if (selectedUris.isNotEmpty()) {
                            Spacer(Modifier.height(16.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(selectedUris) { uriPath: String ->
                                    Box(modifier = Modifier.size(90.dp).clip(RoundedCornerShape(12.dp)).clickable { openFile(context, uriPath) }) {
                                        AsyncImage(model = File(uriPath), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                        Surface(modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(28.dp).clickable { selectedUris = selectedUris - uriPath }, shape = CircleShape, color = Color.Black.copy(alpha = 0.6f)) { Icon(Icons.Default.Close, null, tint = Color.White, modifier = Modifier.padding(6.dp).size(16.dp)) }
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(20.dp))
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.padding(vertical = 4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.TextFields, null, modifier = Modifier.size(18.dp).alpha(0.6f), tint = MaterialTheme.colorScheme.primary)
                                    IconButton(onClick = { if (currentTitleSize > 12) currentTitleSize-- }) { Icon(Icons.Default.Remove, null) }
                                    Text("$currentTitleSize", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                    IconButton(onClick = { if (currentTitleSize < 40) currentTitleSize++ }) { Icon(Icons.Default.Add, null) }
                                }
                                Box(modifier = Modifier.width(1.dp).height(24.dp).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.FormatAlignLeft, null, modifier = Modifier.size(18.dp).alpha(0.6f), tint = MaterialTheme.colorScheme.primary)
                                    IconButton(onClick = { if (currentContentSize > 10) currentContentSize-- }) { Icon(Icons.Default.Remove, null) }
                                    Text("$currentContentSize", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                    IconButton(onClick = { if (currentContentSize < 35) currentContentSize++ }) { Icon(Icons.Default.Add, null) }
                                }
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { filePicker.launch("image/*") }) { Icon(Icons.Default.AttachFile, null, tint = MaterialTheme.colorScheme.primary) }
                            FilterChip(selected = isPinned, onClick = { isPinned = !isPinned }, label = { Text("Pin", fontSize = 12.sp) }, leadingIcon = { Icon(if (isPinned) Icons.Default.PushPin else Icons.Outlined.PushPin, null, Modifier.size(18.dp)) }, shape = RoundedCornerShape(12.dp))
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = { copyToClipboard(context, noteTitle, noteText) }) { Icon(Icons.Default.ContentCopy, null, tint = MaterialTheme.colorScheme.primary) }
                            IconButton(onClick = { shareNote(context, noteTitle, noteText) }) { Icon(Icons.Default.Share, null, tint = MaterialTheme.colorScheme.primary) }
                            if (editingNote != null) { IconButton(onClick = { viewModel.deleteNote(editingNote!!); isDialogOpen = false }) { Icon(Icons.Default.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) } }
                        }
                    }
                },
                confirmButton = { Button(onClick = { saveAction() }, shape = RoundedCornerShape(12.dp)) { Text("Save", fontWeight = FontWeight.Bold) } },
                dismissButton = { TextButton(onClick = { isDialogOpen = false }) { Text("Cancel") } }
            )
        }
    }
}

@Composable
fun NoteCard(note: Note, attachmentCount: Int, onClick: () -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth().wrapContentHeight(), onClick = onClick, shape = RoundedCornerShape(20.dp), colors = CardDefaults.outlinedCardColors(containerColor = if (note.isPriority) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)), border = BorderStroke(1.dp, if (note.isPriority) MaterialTheme.colorScheme.primary else Color.Transparent)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                if (note.title.isNotBlank()) { Text(text = note.title, style = MaterialTheme.typography.titleMedium.copy(fontSize = note.titleSize.sp), fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)) }
                if (note.isPriority) Icon(Icons.Default.PushPin, null, Modifier.size(16.dp).rotate(45f), tint = MaterialTheme.colorScheme.primary)
            }
            if (note.title.isNotBlank()) Spacer(Modifier.height(12.dp))
            Text(text = note.content, style = MaterialTheme.typography.bodyLarge.copy(fontSize = note.contentSize.sp), maxLines = 10, overflow = TextOverflow.Ellipsis)

            if (attachmentCount > 0) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.alpha(0.5f)) {
                    Icon(Icons.Default.AttachFile, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(text = "$attachmentCount", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(themeMode: Int, isBiometricEnabled: Boolean, onThemeChange: (Int) -> Unit, onBiometricToggle: (Boolean) -> Unit) {
    val themes = listOf(Triple("Auto", Icons.Default.AutoMode, 0), Triple("Light", Icons.Default.LightMode, 1), Triple("Dark", Icons.Default.DarkMode, 2), Triple("Black", Icons.Default.NightsStay, 3))
    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Text("Appearance", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            themes.forEach { (name, icon, mode) ->
                val isSelected = themeMode == mode
                Surface(modifier = Modifier.weight(1f).height(90.dp).clickable { onThemeChange(mode) }, shape = RoundedCornerShape(20.dp), color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Icon(icon, null, tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(8.dp)); Text(name, style = MaterialTheme.typography.labelSmall, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) }
                }
            }
        }
        Spacer(Modifier.height(32.dp))
        Text("Security", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 16.dp))
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))) {
            Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Fingerprint, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(16.dp))
                    Column { Text("Biometric Lock", fontWeight = FontWeight.Bold) }
                }
                Switch(checked = isBiometricEnabled, onCheckedChange = { onBiometricToggle(it) })
            }
        }
        Spacer(Modifier.height(32.dp))
        Text("About", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 16.dp))
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Info, null, modifier = Modifier.size(20.dp).alpha(0.6f)); Spacer(Modifier.width(12.dp)); Text("Version 1.0.2 Stable", style = MaterialTheme.typography.bodyMedium) }
                Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Code, null, modifier = Modifier.size(20.dp).alpha(0.6f)); Spacer(Modifier.width(12.dp)); Text("Built with Jetpack Compose", style = MaterialTheme.typography.bodyMedium) }
            }
        }
        Spacer(Modifier.weight(1f))
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Notes", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleLarge, modifier = Modifier.alpha(0.1f))
            Text("FOSS", style = MaterialTheme.typography.labelSmall, modifier = Modifier.alpha(0.2f))
        }
    }
}