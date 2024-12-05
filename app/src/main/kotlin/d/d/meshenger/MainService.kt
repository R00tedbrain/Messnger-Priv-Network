package d.d.meshenger

import android.annotation.SuppressLint
import android.app.*
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.os.*
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import d.d.meshenger.FileUtils.readInternalFile // Importación de FileUtils
import d.d.meshenger.FileUtils.writeInternalFile // Importación de FileUtils
import d.d.meshenger.FileUtils.writeLog // Importación de FileUtils
import d.d.meshenger.FileUtils.readLog // Importación de FileUtils (si necesitas leer logs)
import d.d.meshenger.call.PacketWriter
import d.d.meshenger.call.Pinger
import d.d.meshenger.call.RTCPeerConnection
import org.libsodium.jni.NaCl
import org.libsodium.jni.Sodium
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.*
import java.util.Arrays

class MainService : Service(), Runnable {
    private val binder = MainBinder()
    private var serverSocket: ServerSocket? = null
    private var database: Database? = null
    var firstStart = false
    private var databasePath = ""
    var databasePassword = ""

    @Volatile
    private var isServerSocketRunning = true

    override fun onCreate() {
        super.onCreate()
        databasePath = this.filesDir.toString() + "/database.bin"

        // Inicializar libsodium
        NaCl.sodium()
        writeLog(this, "Libsodium inicializado.") // Logging

        // Cargar la base de datos
        loadDatabase()
        writeLog(this, "Base de datos cargada.") // Logging

        // Inicializar claves y contacto propio
        initializeKeyPairAndContact()
        writeLog(this, "Claves y contacto propio inicializados.") // Logging

        // Manejar conexiones entrantes
        Thread(this).start()
        writeLog(this, "Hilo de servidor iniciado.") // Logging
    }

    private fun initializeKeyPairAndContact() {
        val settings = binder.getSettings()

        // Verificar si la clave pública ya existe
        if (settings.publicKey.isEmpty()) {
            // Generar par de claves Ed25519
            val publicKey = ByteArray(Sodium.crypto_sign_publickeybytes())
            val secretKey = ByteArray(Sodium.crypto_sign_secretkeybytes())
            val result = Sodium.crypto_sign_keypair(publicKey, secretKey)
            if (result != 0) {
                Log.e(TAG, "No se pudo generar el par de claves")
                writeLog(this, "Error: No se pudo generar el par de claves.") // Logging
                return
            }

            // Almacenar la clave pública
            settings.publicKey = publicKey
            writeLog(this, "Clave pública generada y almacenada.") // Logging

            // Cifrar y almacenar la clave privada de forma segura
            val encryptedSecretKey = KeyStoreManager.encryptData(this, secretKey)
            if (encryptedSecretKey != null) {
                settings.encryptedSecretKey = encryptedSecretKey
                writeLog(this, "Clave privada cifrada y almacenada.") // Logging
            } else {
                Log.e(TAG, "No se pudo cifrar la clave privada")
                writeLog(this, "Error: No se pudo cifrar la clave privada.") // Logging
            }

            // Limpiar la clave privada de la memoria
            Arrays.fill(secretKey, 0.toByte())

            saveDatabase()
            writeLog(this, "Base de datos guardada después de generar claves.") // Logging
        }

        // Crear el contacto propio si no existe
        if (settings.ownContact == null) {
            val ownContact = Contact(
                name = if (settings.username.isNotEmpty()) settings.username else getString(R.string.default_username),
                publicKey = settings.publicKey,
                addresses = settings.addresses.toMutableList()
                // Otros campos necesarios...
            )
            settings.ownContact = ownContact
            saveDatabase()
            writeLog(this, "Contacto propio creado y base de datos guardada.") // Logging
        }
    }

    private fun createNotification(text: String): Notification {
        Log.d(TAG, "createNotification() text=$text")
        writeLog(this, "Creando notificación: $text") // Logging
        val channelId = "meshenger_service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                channelId,
                "Meshenger Call Listener",
                NotificationManager.IMPORTANCE_LOW // mostrar notificación como colapsada por defecto
            )
            chan.lightColor = Color.RED
            chan.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            val service = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            service.createNotificationChannel(chan)
            writeLog(this, "Canal de notificación creado.") // Logging
        }

        // iniciar MainActivity
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingNotificationIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        } else {
            PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT)
        }

        return NotificationCompat.Builder(applicationContext, channelId)
            .setSilent(true)
            .setOngoing(true)
            .setShowWhen(false)
            .setUsesChronometer(false)
            .setSmallIcon(R.drawable.ic_logo)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentText(text)
            .setContentIntent(pendingNotificationIntent)
            .build()
    }

    fun loadDatabase() {
        writeLog(this, "Cargando base de datos desde $databasePath") // Logging
        val dbBytes = readInternalFile(this, databasePath)
        if (dbBytes != null) {
            // abrir base de datos existente
            database = Database.fromData(dbBytes, databasePassword)
            firstStart = false
            writeLog(this, "Base de datos existente cargada.") // Logging
        } else {
            // crear nueva base de datos
            database = Database()
            firstStart = true
            writeLog(this, "Nueva base de datos creada.") // Logging
        }
    }

    fun mergeDatabase(newDb: Database) {
        writeLog(this, "Fusionando nueva base de datos.") // Logging
        val oldDatabase = database!!

        oldDatabase.settings = newDb.settings

        for (contact in newDb.contacts.contactList) {
            oldDatabase.contacts.addContact(contact)
        }

        for (event in newDb.events.eventList) {
            oldDatabase.events.addEvent(event)
        }
    }

    fun saveDatabase() {
        try {
            val db = database
            if (db != null) {
                val dbData = Database.toData(db, databasePassword)
                if (dbData != null) {
                    writeInternalFile(this, databasePath, dbData)
                    writeLog(this, "Base de datos guardada en $databasePath") // Logging
                } else {
                    writeLog(this, "Error: dbData es null.") // Logging
                }
            } else {
                writeLog(this, "Error: database es null.") // Logging
            }
        } catch (e: Exception) {
            e.printStackTrace()
            writeLog(this, "Excepción al guardar la base de datos: ${e.message}") // Logging
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy()")
        writeLog(this, "Servicio destruido.") // Logging
        isServerSocketRunning = false

        // decir adiós
        val database = this.database
        if (database != null && serverSocket != null && serverSocket!!.isBound && !serverSocket!!.isClosed) {
            try {
                val message = "{\"action\": \"status_change\", \"status\": \"offline\"}"
                for (contact in database.contacts.contactList) {
                    if (contact.state != Contact.State.CONTACT_ONLINE) {
                        continue
                    }
                    val encrypted = Crypto.encryptMessage(message, contact.publicKey, this) ?: continue
                    var socket: Socket? = null
                    try {
                        val settings = binder.getSettings()
                        val connector = Connector(
                            settings.connectTimeout,
                            1, // solo intentar una vez, esto es de baja prioridad
                            settings.guessEUI64Address,
                            settings.useNeighborTable
                        )
                        socket = connector.connect(contact)
                        if (socket == null) {
                            continue
                        }
                        val pw = PacketWriter(socket)
                        pw.writeMessage(encrypted)
                        writeLog(this, "Mensaje de estado enviado a ${contact.name}") // Logging
                    } catch (_: Exception) {
                        // ignorar
                        writeLog(this, "Error al enviar mensaje de estado a ${contact.name}") // Logging
                    } finally {
                        AddressUtils.closeSocket(socket)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                writeLog(this, "Excepción en onDestroy al enviar mensajes: ${e.message}") // Logging
            }
        }

        try {
            serverSocket?.close()
            writeLog(this, "ServerSocket cerrado.") // Logging
        } catch (e: Exception) {
            // ignorar
            writeLog(this, "Error al cerrar ServerSocket: ${e.message}") // Logging
        }

        // guardar base de datos al salir
        saveDatabase()
        database?.destroy()
        writeLog(this, "Base de datos destruida.") // Logging

        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand()")
        writeLog(this, "onStartCommand() llamado.") // Logging

        if (intent == null || intent.action == null) {
            Log.d(TAG, "onStartCommand() Recibido intent inválido")
            writeLog(this, "Intent inválido recibido en onStartCommand().") // Logging
        } else if (intent.action == START_FOREGROUND_ACTION) {
            Log.d(TAG, "onStartCommand() Recibido Start Foreground Intent")
            writeLog(this, "Start Foreground Intent recibido.") // Logging
            val message = resources.getText(R.string.listen_for_incoming_calls).toString()
            val notification = createNotification(message)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                startForeground(NOTIFICATION_ID, notification)
            } else {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            }
            writeLog(this, "Servicio iniciado en primer plano.") // Logging
        } else if (intent.action == STOP_FOREGROUND_ACTION) {
            Log.d(TAG, "onStartCommand() Recibido Stop Foreground Intent")
            writeLog(this, "Stop Foreground Intent recibido.") // Logging
            shutdown()
        }
        return START_NOT_STICKY
    }

    override fun run() {
        writeLog(this, "Hilo run() iniciado.") // Logging
        try {
            // esperar hasta que la base de datos esté lista
            while (database == null && isServerSocketRunning) {
                try {
                    Thread.sleep(1000)
                } catch (e: Exception) {
                    writeLog(this, "Excepción en el hilo run(): ${e.message}") // Logging
                    break
                }
            }
            serverSocket = ServerSocket(SERVER_PORT)
            writeLog(this, "ServerSocket escuchando en el puerto $SERVER_PORT") // Logging
            while (isServerSocketRunning) {
                try {
                    val socket = serverSocket!!.accept()
                    Log.d(TAG, "run() nueva conexión entrante")
                    writeLog(this, "Nueva conexión entrante aceptada.") // Logging
                    RTCPeerConnection.createIncomingCall(binder, socket)
                } catch (e: IOException) {
                    Log.e(TAG, "run() IOException: ${e.message}")
                    writeLog(this, "IOException en run(): ${e.message}") // Logging
                    // ignorar
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "run() e=$e")
            e.printStackTrace()
            writeLog(this, "Excepción en run(): ${e.message}") // Logging
            Handler(mainLooper).post { Toast.makeText(this, e.message, Toast.LENGTH_LONG).show() }
            shutdown()
        }
    }

    @SuppressLint("StringFormatMatches")
    private fun updateNotification() {
        Log.d(TAG, "updateNotification()")
        writeLog(this, "Actualizando notificación.") // Logging

        val eventList = binder.getEvents().eventList
        val eventsMissed = binder.getEvents().eventsMissed
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val message = if (eventList.isEmpty() || eventsMissed == 0) {
            // mensaje por defecto
            resources.getText(R.string.listen_for_incoming_calls).toString()
        } else {
            // llamadas perdidas
            val publicKey = eventList.last().publicKey
            val contact = binder.getContacts().getContactByPublicKey(publicKey)
            val name = contact?.name ?: getString(R.string.unknown_caller)
            // Ajustamos la llamada a String.format para suministrar el número correcto de argumentos
            String.format(getString(R.string.missed_call_from), eventsMissed, name)
        }
        val notification = createNotification(message)
        manager.notify(NOTIFICATION_ID, notification)
        writeLog(this, "Notificación actualizada con mensaje: $message") // Logging
    }

    /*
    * Permite la comunicación entre MainService y otros objetos
    */
    inner class MainBinder : Binder() {
        fun getService(): MainService {
            writeLog(this@MainService, "MainBinder: getService() llamado.") // Logging
            return this@MainService
        }

        fun isDatabaseLoaded(): Boolean {
            writeLog(this@MainService, "MainBinder: isDatabaseLoaded() llamado.") // Logging
            return database != null
        }

        fun getDatabase(): Database {
            if (database == null) {
                Log.e(TAG, "getDatabase() database es null => intentar recargar")
                writeLog(this@MainService, "getDatabase() database es null, intentando recargar.") // Logging
                try {
                    // la base de datos es nula, esto no debería suceder, pero
                    // sucede de todos modos, así que vamos a mitigarlo por ahora
                    // => intentar recargarla
                    loadDatabase()
                    writeLog(this@MainService, "Base de datos recargada correctamente.") // Logging
                } catch (e: Exception) {
                    Log.e(TAG, "getDatabase() no se pudo recargar la base de datos")
                    writeLog(this@MainService, "Error al recargar la base de datos: ${e.message}") // Logging
                }
            }
            return database!!
        }

        fun getSettings(): Settings {
            writeLog(this@MainService, "MainBinder: getSettings() llamado.") // Logging
            return getDatabase().settings
        }

        fun getContacts(): Contacts {
            writeLog(this@MainService, "MainBinder: getContacts() llamado.") // Logging
            return getDatabase().contacts
        }

        fun getEvents(): Events {
            writeLog(this@MainService, "MainBinder: getEvents() llamado.") // Logging
            return getDatabase().events
        }

        fun getContactOrOwn(otherPublicKey: ByteArray): Contact? {
            writeLog(this@MainService, "MainBinder: getContactOrOwn() llamado.") // Logging
            val db = getDatabase()
            val ownPublicKey = db.settings.publicKey
            return if (ownPublicKey.contentEquals(otherPublicKey)) {
                db.settings.ownContact
            } else {
                db.contacts.getContactByPublicKey(otherPublicKey)
            }
        }

        fun updateNotification() {
            writeLog(this@MainService, "MainBinder: updateNotification() llamado.") // Logging
            this@MainService.updateNotification()
        }

        fun addContact(contact: Contact) {
            writeLog(this@MainService, "MainBinder: addContact() llamado para ${contact.name}") // Logging
            getDatabase().contacts.addContact(contact)
            saveDatabase()

            pingContacts(listOf(contact))

            refreshContacts(this@MainService)
            refreshEvents(this@MainService)
            writeLog(this@MainService, "Contacto ${contact.name} agregado y notificado.") // Logging
        }

        fun deleteContact(publicKey: ByteArray) {
            writeLog(this@MainService, "MainBinder: deleteContact() llamado.") // Logging
            getDatabase().contacts.deleteContact(publicKey)
            saveDatabase()

            refreshContacts(this@MainService)
            refreshEvents(this@MainService)
            writeLog(this@MainService, "Contacto eliminado y notificado.") // Logging
        }

        fun deleteEvents(eventDates: List<Date>) {
            writeLog(this@MainService, "MainBinder: deleteEvents() llamado.") // Logging
            getDatabase().events.deleteEvents(eventDates)
            saveDatabase()

            refreshContacts(this@MainService)
            refreshEvents(this@MainService)
            writeLog(this@MainService, "Eventos eliminados y notificados.") // Logging
        }

        fun shutdown() {
            writeLog(this@MainService, "MainBinder: shutdown() llamado.") // Logging
            this@MainService.shutdown()
        }

        fun pingContacts(contactList: List<Contact>) {
            writeLog(this@MainService, "MainBinder: pingContacts() llamado para ${contactList.size} contactos.") // Logging
            Log.d(TAG, "pingContacts()")
            Thread(
                Pinger(binder, contactList)
            ).start()
        }

        fun saveDatabase() {
            writeLog(this@MainService, "MainBinder: saveDatabase() llamado.") // Logging
            this@MainService.saveDatabase()
        }

        fun addEvent(event: Event) {
            Log.d(TAG, "addEvent() event.type=${event.type}")
            writeLog(this@MainService, "MainBinder: addEvent() llamado para evento tipo ${event.type}") // Logging

            // actualizar notificación
            if (event.type == Event.Type.INCOMING_MISSED) {
                getEvents().eventsMissed += 1
                updateNotification()
                writeLog(this@MainService, "Evento INCOMING_MISSED incrementado.") // Logging
            }

            if (!getSettings().disableCallHistory) {
                getEvents().addEvent(event)
                saveDatabase()
                refreshEvents(this@MainService)
                writeLog(this@MainService, "Evento agregado y notificado.") // Logging
            }
        }

        fun clearEvents() {
            writeLog(this@MainService, "MainBinder: clearEvents() llamado.") // Logging
            getEvents().clearEvents()
            refreshEvents(this@MainService)
            writeLog(this@MainService, "Eventos limpiados y notificados.") // Logging
        }
    }

    private fun shutdown() {
        Log.i(TAG, "shutdown()")
        writeLog(this, "shutdown() llamado.") // Logging
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID)
        stopSelf()
        writeLog(this, "Servicio detenido.") // Logging
    }

    override fun onBind(intent: Intent): IBinder {
        writeLog(this, "onBind() llamado.") // Logging
        return binder
    }

    companion object {
        const val SERVER_PORT = 10001
        private const val START_FOREGROUND_ACTION = "START_FOREGROUND_ACTION"
        private const val STOP_FOREGROUND_ACTION = "STOP_FOREGROUND_ACTION"
        private const val NOTIFICATION_ID = 42
        private const val TAG = "MainService"

        fun start(ctx: Context) {
            val startIntent = Intent(ctx, MainService::class.java)
            startIntent.action = START_FOREGROUND_ACTION
            ContextCompat.startForegroundService(ctx, startIntent)
            FileUtils.writeLog(ctx, "Servicio iniciado en primer plano.") // Logging
        }

        fun stop(ctx: Context) {
            val stopIntent = Intent(ctx, MainService::class.java)
            stopIntent.action = STOP_FOREGROUND_ACTION
            ctx.startService(stopIntent)
            FileUtils.writeLog(ctx, "Servicio detenido desde Companion.") // Logging
        }

        fun refreshContacts(ctx: Context) {
            LocalBroadcastManager.getInstance(ctx)
                .sendBroadcast(Intent("refresh_contact_list"))
            FileUtils.writeLog(ctx, "Broadcast para refrescar lista de contactos enviado.") // Logging
        }

        fun refreshEvents(ctx: Context) {
            LocalBroadcastManager.getInstance(ctx)
                .sendBroadcast(Intent("refresh_event_list"))
            FileUtils.writeLog(ctx, "Broadcast para refrescar lista de eventos enviado.") // Logging
        }
    }
}
