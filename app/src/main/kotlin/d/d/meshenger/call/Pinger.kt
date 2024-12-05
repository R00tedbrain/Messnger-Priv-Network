package d.d.meshenger.call

import d.d.meshenger.*
import d.d.meshenger.AddressUtils
import org.json.JSONObject
import org.libsodium.jni.Sodium
import java.net.Socket

/*
 * Verifica si un contacto está en línea.
 */
class Pinger(val binder: MainService.MainBinder, val contacts: List<Contact>) : Runnable {
    private fun pingContact(contact: Contact): Contact.State {
        Log.d(this, "pingContact() contact: ${contact.name}")

        val otherPublicKey = ByteArray(Sodium.crypto_sign_publickeybytes())
        val settings = binder.getSettings()
        val context = binder.getService()
        var socket: Socket? = null

        try {
            val connector = Connector(
                settings.connectTimeout,
                settings.connectRetries,
                settings.guessEUI64Address,
                settings.useNeighborTable
            )
            socket = connector.connect(contact)

            if (socket == null) {
                return if (connector.networkNotReachable) {
                    Contact.State.NETWORK_UNREACHABLE
                } else {
                    Contact.State.CONTACT_OFFLINE
                }
            }

            socket.soTimeout = 3000

            val pw = PacketWriter(socket)
            val pr = PacketReader(socket)

            Log.d(this, "pingContact() send ping to ${contact.name}")
            val encrypted = Crypto.encryptMessage(
                "{\"action\":\"ping\"}",
                contact.publicKey,
                context
            ) ?: return Contact.State.COMMUNICATION_FAILED

            pw.writeMessage(encrypted)
            val request = pr.readMessage() ?: return Contact.State.COMMUNICATION_FAILED
            val decrypted = Crypto.decryptMessage(
                request,
                otherPublicKey,
                context
            ) ?: return Contact.State.AUTHENTICATION_FAILED

            if (!otherPublicKey.contentEquals(contact.publicKey)) {
                return Contact.State.AUTHENTICATION_FAILED
            }

            val obj = JSONObject(decrypted)
            val action = obj.optString("action", "")
            return if (action == "pong") {
                Log.d(this, "pingContact() got pong")
                Contact.State.CONTACT_ONLINE
            } else {
                Contact.State.COMMUNICATION_FAILED
            }
        } catch (e: Exception) {
            return Contact.State.COMMUNICATION_FAILED
        } finally {
            // asegurarse de cerrar el socket
            AddressUtils.closeSocket(socket)
        }
    }

    override fun run() {
        // establecer todos los estados a desconocido
        for (contact in contacts) {
            binder.getContacts()
                .getContactByPublicKey(contact.publicKey)
                ?.state = Contact.State.PENDING
        }

        MainService.refreshContacts(binder.getService())
        MainService.refreshEvents(binder.getService())

        // ping a los contactos
        for (contact in contacts) {
            val state = pingContact(contact)
            Log.d(this, "contact state is $state")

            // establecer estado del contacto
            binder.getContacts()
                .getContactByPublicKey(contact.publicKey)
                ?.state = state
        }

        MainService.refreshContacts(binder.getService())
        MainService.refreshEvents(binder.getService())
    }
}
