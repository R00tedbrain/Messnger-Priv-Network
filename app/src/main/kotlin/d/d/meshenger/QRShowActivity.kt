package d.d.meshenger

import android.app.Activity
import android.content.*
import android.graphics.Bitmap
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.journeyapps.barcodescanner.BarcodeEncoder
import d.d.meshenger.MainService.MainBinder
import d.d.meshenger.FileUtils // Importación del objeto FileUtils

class QRShowActivity : BaseActivity(), ServiceConnection {
    private var publicKey: ByteArray? = null
    private var contact: Contact? = null
    private var binder: MainBinder? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_qrshow)

        // Obtenemos el publicKey de los extras del intent
        val publicKeyHex = intent.extras?.getString("EXTRA_CONTACT_PUBLICKEY")
        if (publicKeyHex != null) {
            publicKey = FileUtils.hexStringToByteArray(this, publicKeyHex) // Usando FileUtils con contexto
        } else {
            // Si no se proporciona, asignamos null a publicKey
            publicKey = null
        }

        title = getString(R.string.title_show_qr_code)

        // Iniciamos el servicio y lo vinculamos
        bindService(Intent(this, MainService::class.java), this, 0)

        findViewById<View>(R.id.fabScan).setOnClickListener {
            startActivity(Intent(this, QRScanActivity::class.java))
            finish()
        }

        // Los listeners que dependen de 'contact' se configurarán en 'onServiceConnected'
    }

    override fun onDestroy() {
        super.onDestroy()
        if (binder != null) {
            unbindService(this)
        }
    }

    private fun showQRCode() {
        val contact = contact
        if (contact == null) {
            Toast.makeText(this, R.string.error_contact_not_found, Toast.LENGTH_SHORT).show()
            return
        }

        findViewById<TextView>(R.id.contact_name_tv).text = contact.name

        val bitmap = contactToBitmap(contact)
        findViewById<ImageView>(R.id.QRView).setImageBitmap(bitmap)

        if (contact.addresses.isEmpty()) {
            Toast.makeText(this, R.string.contact_has_no_address_warning, Toast.LENGTH_SHORT).show()
        }
    }

    private var exportFileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data ?: return@registerForActivityResult
            try {
                val contact = contact
                if (contact != null) {
                    val bitmap = contactToBitmap(contact)
                    val outStream = contentResolver.openOutputStream(uri)
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, outStream!!)
                    outStream.close()
                    Toast.makeText(this, R.string.done, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, R.string.error_contact_not_found, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this, R.string.failed_to_export_database, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onServiceConnected(componentName: ComponentName, iBinder: IBinder) {
        binder = iBinder as? MainBinder

        if (binder == null) {
            Toast.makeText(this, R.string.error_service_connection_failed, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        try {
            contact = if (publicKey != null && publicKey!!.isNotEmpty()) {
                binder!!.getContactOrOwn(publicKey!!)
            } else {
                binder!!.getSettings().ownContact
            }

            if (contact == null) {
                val errorMessage = getString(R.string.error_contact_not_found)
                Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show()
                finish()
                return
            }

            showQRCode()
        } catch (e: Exception) {
            e.printStackTrace()
            val errorMessage = e.message ?: getString(R.string.error)
            // Asegurarnos de que errorMessage no es nulo
            if (errorMessage.isNullOrEmpty()) {
                Toast.makeText(this, R.string.error, Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show()
            }
            finish()
            return
        }

        // Ahora que 'contact' está inicializado, configuramos los listeners que dependen de él
        findViewById<View>(R.id.fabSave).setOnClickListener {
            val contact = contact
            if (contact != null) {
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
                intent.addCategory(Intent.CATEGORY_OPENABLE)
                intent.putExtra(Intent.EXTRA_TITLE, "${contact.name}_qr-code.png")
                intent.type = "image/png"
                exportFileLauncher.launch(intent)
            } else {
                Toast.makeText(this, R.string.error_contact_not_found, Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<View>(R.id.fabShare).setOnClickListener {
            val contact = contact
            if (contact != null) {
                try {
                    val data = Contact.toJSON(contact, false).toString()
                    val intent = Intent(Intent.ACTION_SEND)
                    intent.putExtra(Intent.EXTRA_TEXT, data)
                    intent.type = "text/plain"
                    startActivity(intent)
                    finish()
                } catch (e: Exception) {
                    // Ignoramos la excepción
                }
            } else {
                Toast.makeText(this, R.string.error_contact_not_found, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onServiceDisconnected(componentName: ComponentName) {
        binder = null
    }

    companion object {
        private fun contactToBitmap(contact: Contact): Bitmap {
            val data = Contact.toJSON(contact, false).toString()
            val hints = mapOf(EncodeHintType.CHARACTER_SET to "utf-8")
            val multiFormatWriter = MultiFormatWriter()
            val bitMatrix = multiFormatWriter.encode(data, BarcodeFormat.QR_CODE, 1080, 1080, hints)
            val barcodeEncoder = BarcodeEncoder()
            return barcodeEncoder.createBitmap(bitMatrix)
        }
    }
}
