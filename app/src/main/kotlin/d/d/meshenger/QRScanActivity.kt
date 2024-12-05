package d.d.meshenger

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.*
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ResultPoint
import com.google.zxing.common.HybridBinarizer
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import org.json.JSONException
import org.json.JSONObject
import kotlin.math.floor

class QRScanActivity : BaseActivity(), BarcodeCallback, ServiceConnection {
    private lateinit var barcodeView: DecoratedBarcodeView
    private var binder: MainService.MainBinder? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_qrscan)
        title = getString(R.string.title_scan_qr_code)

        barcodeView = findViewById(R.id.barcodeScannerView)

        bindService(Intent(this, MainService::class.java), this, 0)

        // button to show QR-Code
        findViewById<View>(R.id.fabCameraInput).setOnClickListener {
            val intent = Intent(this, QRShowActivity::class.java)
            intent.putExtra(
                "EXTRA_CONTACT_PUBLICKEY",
                Base64.encodeToString(binder!!.getSettings().publicKey, Base64.NO_WRAP)
            )
            startActivity(intent)
            finish()
        }

        // button for manual input
        findViewById<View>(R.id.fabManualInput).setOnClickListener { startManualInput() }

        // button to get QR-Code from image
        findViewById<View>(R.id.fabImageInput).setOnClickListener { startImageInput() }

        if (!Utils.hasPermission(this, Manifest.permission.CAMERA)) {
            enabledCameraForResult.launch(Manifest.permission.CAMERA)
        } else {
            initCamera()
        }
    }

    private val enabledCameraForResult =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                initCamera()
            } else {
                Toast.makeText(this, R.string.missing_camera_permission, Toast.LENGTH_LONG).show()
                // no finish() in case no camera access wanted but contact data pasted
            }
        }

    private fun addContact(data: String) {
        val obj = JSONObject(data)
        val newContact = Contact.fromJSON(obj, false)
        if (newContact.addresses.isEmpty()) {
            Toast.makeText(this, R.string.contact_has_no_address_warning, Toast.LENGTH_LONG).show()
        }

        // Mostrar diálogo para ingresar nombre y mostrar la huella digital
        showAddContactDialog(newContact)
    }

    private fun showAddContactDialog(contact: Contact) {
        barcodeView.pause()

        val builder = AlertDialog.Builder(this)
        val inflater = LayoutInflater.from(this)
        val dialogView = inflater.inflate(R.layout.dialog_add_contact, null)
        builder.setView(dialogView)

        val nameEditText = dialogView.findViewById<EditText>(R.id.NameEditText)
        val fingerprintTextView = dialogView.findViewById<TextView>(R.id.fingerprint_textview)

        // Generar la huella digital del contacto usando Crypto.kt
        val fingerprint = Crypto.generateFingerprint(contact.publicKey)

        // Establecer la huella digital en el TextView
        fingerprintTextView.text = getString(R.string.contact_fingerprint, fingerprint)

        builder.setTitle(R.string.add_contact)
        builder.setPositiveButton(R.string.button_ok) { _, _ ->
            val name = nameEditText.text.toString()
            if (name.isNotEmpty()) {
                contact.name = name

                // Verificar conflictos antes de agregar el contacto
                val contacts = binder?.getContacts() ?: return@setPositiveButton
                val existingContactByPublicKey = contacts.getContactByPublicKey(contact.publicKey)
                val existingContactByName = contacts.getContactByName(contact.name)

                if (existingContactByPublicKey != null) {
                    // Contacto con esa clave pública ya existe
                    showPubkeyConflictDialog(contact, existingContactByPublicKey)
                } else if (existingContactByName != null) {
                    // Contacto con ese nombre ya existe
                    showNameConflictDialog(contact, existingContactByName)
                } else {
                    // No hay conflictos
                    binder?.addContact(contact)
                    Toast.makeText(this, R.string.contact_added, Toast.LENGTH_SHORT).show()
                    finish()
                }
            } else {
                Toast.makeText(this, R.string.enter_contact_name, Toast.LENGTH_SHORT).show()
            }
        }
        builder.setNegativeButton(R.string.button_cancel) { _, _ ->
            barcodeView.resume()
        }

        val dialog = builder.create()
        dialog.show()
    }

    private fun showPubkeyConflictDialog(newContact: Contact, otherContact: Contact) {
        barcodeView.pause()

        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_add_contact_pubkey_conflict)
        val nameTextView = dialog.findViewById<TextView>(R.id.public_key_conflicting_contact_textview)
        val abortButton = dialog.findViewById<Button>(R.id.public_key_conflict_abort_button)
        val replaceButton = dialog.findViewById<Button>(R.id.public_key_conflict_replace_button)
        nameTextView.text = otherContact.name
        replaceButton.setOnClickListener {
            binder?.deleteContact(otherContact.publicKey)
            binder?.addContact(newContact)

            // done
            Toast.makeText(this@QRScanActivity, R.string.done, Toast.LENGTH_SHORT).show()
            dialog.cancel()
            finish()
        }
        abortButton.setOnClickListener {
            dialog.cancel()
            barcodeView.resume()
        }
        dialog.show()
    }

    private fun showNameConflictDialog(newContact: Contact, otherContact: Contact) {
        barcodeView.pause()

        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_add_contact_name_conflict)
        val nameEditText = dialog.findViewById<EditText>(R.id.conflict_contact_edit_textview)
        val abortButton = dialog.findViewById<Button>(R.id.conflict_contact_abort_button)
        val replaceButton = dialog.findViewById<Button>(R.id.conflict_contact_replace_button)
        val renameButton = dialog.findViewById<Button>(R.id.conflict_contact_rename_button)
        nameEditText.setText(otherContact.name)
        replaceButton.setOnClickListener {
            binder?.deleteContact(otherContact.publicKey)
            binder?.addContact(newContact)

            // done
            Toast.makeText(this@QRScanActivity, R.string.done, Toast.LENGTH_SHORT).show()
            dialog.cancel()
            finish()
        }
        renameButton.setOnClickListener {
            val name = nameEditText.text.toString()
            if (name.isEmpty()) {
                Toast.makeText(this, R.string.contact_name_invalid, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (binder?.getContacts()?.getContactByName(name) != null) {
                Toast.makeText(this, R.string.contact_name_exists, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // rename
            newContact.name = name
            binder?.addContact(newContact)

            // done
            Toast.makeText(this@QRScanActivity, R.string.done, Toast.LENGTH_SHORT).show()
            dialog.cancel()
            finish()
        }
        abortButton.setOnClickListener {
            dialog.cancel()
            barcodeView.resume()
        }
        dialog.show()
    }

    private fun startManualInput() {
        barcodeView.pause()
        val b = AlertDialog.Builder(this, R.style.AlertDialogTheme)
        val et = EditText(this)
        b.setTitle(R.string.paste_qr_code_data)
            .setPositiveButton(R.string.button_ok) { _, _ ->
                try {
                    val data = et.text.toString()
                    addContact(data)
                } catch (e: JSONException) {
                    e.printStackTrace()
                    Toast.makeText(this, R.string.invalid_qr_code_data, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.button_cancel) { _, _ ->
                barcodeView.resume()
            }
            .setView(et)
        b.show()
    }

    private val importFileLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val uri = result.data?.data ?: return@registerForActivityResult
                try {
                    val bitmap = getThumbnail(applicationContext, uri)
                    val reader = MultiFormatReader()
                    val qrResult = reader.decode(convertToBinaryBitmap(bitmap))
                    addContact(qrResult.text)
                    barcodeView.resume()
                } catch (e: Exception) {
                    Toast.makeText(this, R.string.invalid_qr_code_data, Toast.LENGTH_SHORT).show()
                }
            }
        }

    private fun startImageInput() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "image/*"
        importFileLauncher.launch(intent)
        barcodeView.pause()
    }

    override fun barcodeResult(result: BarcodeResult) {
        // no more scan until result is processed
        try {
            addContact(result.text)
        } catch (e: JSONException) {
            e.printStackTrace()
            Toast.makeText(this, R.string.invalid_qr, Toast.LENGTH_LONG).show()
        }
    }

    override fun possibleResultPoints(resultPoints: List<ResultPoint>) {
        // ignore
    }

    override fun onResume() {
        super.onResume()
        binder?.let {
            barcodeView.resume()
        }
    }

    override fun onPause() {
        super.onPause()
        binder?.let {
            barcodeView.pause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unbindService(this)
    }

    private fun initCamera() {
        val formats = listOf(BarcodeFormat.QR_CODE)
        barcodeView.barcodeView?.decoderFactory = DefaultDecoderFactory(formats)
        barcodeView.decodeContinuous(this)
        barcodeView.resume()
    }

    override fun onServiceConnected(componentName: ComponentName, iBinder: IBinder) {
        binder = iBinder as MainService.MainBinder
        if (Utils.hasPermission(this, Manifest.permission.CAMERA)) {
            initCamera()
        }
    }

    override fun onServiceDisconnected(componentName: ComponentName) {
        binder = null
    }

    companion object {
        // make image smaller for less resource use
        private fun getThumbnail(ctx: Context, uri: Uri): Bitmap {
            val size = 800

            // open image stream to get size
            val input = ctx.contentResolver.openInputStream(uri)
                ?: throw Exception("Cannot open picture")

            val onlyBoundsOptions = BitmapFactory.Options()
            onlyBoundsOptions.inJustDecodeBounds = true
            onlyBoundsOptions.inPreferredConfig = Bitmap.Config.ARGB_8888 // optional
            BitmapFactory.decodeStream(input, null, onlyBoundsOptions)
            input.close()

            if ((onlyBoundsOptions.outWidth == -1) || (onlyBoundsOptions.outHeight == -1)) {
                throw Exception("Internal error")
            }

            fun getPowerOfTwoForSampleRatio(ratio: Double): Int {
                val k = Integer.highestOneBit(floor(ratio).toInt())
                return if (k == 0) 1 else k
            }

            val originalSize = maxOf(onlyBoundsOptions.outHeight, onlyBoundsOptions.outWidth)
            val ratio = if ((originalSize > size)) (originalSize / size.toDouble()) else 1.0

            val bitmapOptions = BitmapFactory.Options()
            bitmapOptions.inSampleSize = getPowerOfTwoForSampleRatio(ratio)
            bitmapOptions.inPreferredConfig = Bitmap.Config.ARGB_8888

            // open image stream to resize while decoding
            val stream = ctx.contentResolver.openInputStream(uri)
                ?: throw Exception("Cannot open stream")
            val bitmap: Bitmap?
            try {
                bitmap = BitmapFactory.decodeStream(stream, null, bitmapOptions)
            } finally {
                stream.close()
            }

            return bitmap ?: throw Exception("Cannot decode bitmap")
        }

        private fun convertToBinaryBitmap(bitmap: Bitmap): BinaryBitmap {
            val intArray = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(intArray, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val source = RGBLuminanceSource(bitmap.width, bitmap.height, intArray)
            return BinaryBitmap(HybridBinarizer(source))
        }
    }
}
