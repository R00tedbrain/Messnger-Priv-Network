package d.d.meshenger

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

object FileUtils {

    private const val LOG_DIR = "logs"
    private const val LOG_FILE = "mi_exception_log.txt"

    /**
     * Escribe un mensaje en el archivo de log.
     *
     * @param context Contexto de la aplicación.
     * @param message Mensaje a escribir en el log.
     */
    fun writeLog(context: Context, message: String) {
        try {
            val logDir = File(context.filesDir, LOG_DIR)
            if (!logDir.exists()) {
                val dirCreated = logDir.mkdirs()
                if (!dirCreated) {
                    Log.e("FileUtils", "No se pudo crear el directorio de logs.")
                    return
                }
            }
            val logFile = File(logDir, LOG_FILE)
            FileOutputStream(logFile, true).use { fos ->
                fos.write("$message\n".toByteArray())
            }
        } catch (e: IOException) {
            Log.e("FileUtils", "Error al escribir en el log: ${e.message}")
        }
    }

    /**
     * Lee el contenido del archivo de log.
     *
     * @param context Contexto de la aplicación.
     * @return Contenido del log como String, o null si falla.
     */
    fun readLog(context: Context): String? {
        return try {
            val logDir = File(context.filesDir, LOG_DIR)
            val logFile = File(logDir, LOG_FILE)
            if (logFile.exists()) {
                FileInputStream(logFile).use { fis ->
                    fis.bufferedReader().use { it.readText() }
                }
            } else {
                null
            }
        } catch (e: IOException) {
            Log.e("FileUtils", "Error al leer el log: ${e.message}")
            null
        }
    }

    /**
     * Limpia el contenido del archivo de log.
     *
     * @param context Contexto de la aplicación.
     */
    fun clearLog(context: Context) {
        try {
            val logDir = File(context.filesDir, LOG_DIR)
            val logFile = File(logDir, LOG_FILE)
            if (logFile.exists()) {
                val fileCleared = logFile.delete()
                if (fileCleared) {
                    logFile.createNewFile()
                } else {
                    Log.e("FileUtils", "No se pudo limpiar el archivo de logs.")
                }
            } else {
                logFile.createNewFile()
            }
        } catch (e: IOException) {
            Log.e("FileUtils", "Error al limpiar el log: ${e.message}")
        }
    }

    /**
     * Escribe datos en un archivo interno.
     *
     * @param context Contexto de la aplicación.
     * @param filename Nombre del archivo.
     * @param data Datos a escribir en el archivo.
     */
    fun writeInternalFile(context: Context, filename: String, data: ByteArray) {
        try {
            val file = File(context.filesDir, filename)
            FileOutputStream(file).use { fos ->
                fos.write(data)
            }
            writeLog(context, "Datos escritos en el archivo $filename.")
        } catch (e: IOException) {
            Log.e("FileUtils", "Error al escribir en $filename: ${e.message}")
            writeLog(context, "Error al escribir en $filename: ${e.message}")
        }
    }

    /**
     * Lee datos de un archivo interno.
     *
     * @param context Contexto de la aplicación.
     * @param filename Nombre del archivo.
     * @return Datos leídos del archivo como ByteArray, o null si falla.
     */
    fun readInternalFile(context: Context, filename: String): ByteArray? {
        return try {
            val file = File(context.filesDir, filename)
            if (file.exists()) {
                FileInputStream(file).use { fis ->
                    fis.readBytes()
                }.also {
                    writeLog(context, "Datos leídos del archivo $filename.")
                }
            } else {
                writeLog(context, "Archivo $filename no existe.")
                null
            }
        } catch (e: IOException) {
            Log.e("FileUtils", "Error al leer de $filename: ${e.message}")
            writeLog(context, "Error al leer de $filename: ${e.message}")
            null
        }
    }

    /**
     * Convierte una cadena hexadecimal a un ByteArray.
     *
     * @param context Contexto de la aplicación.
     * @param hexString La cadena hexadecimal.
     * @return El ByteArray resultante, o null si la cadena no es válida.
     */
    fun hexStringToByteArray(context: Context, hexString: String): ByteArray? {
        return try {
            val len = hexString.length
            if (len % 2 != 0) {
                throw IllegalArgumentException("La cadena hexadecimal debe tener un número par de caracteres.")
            }
            val data = ByteArray(len / 2)
            for (i in 0 until len step 2) {
                data[i / 2] = ((Character.digit(hexString[i], 16) shl 4)
                        + Character.digit(hexString[i + 1], 16)).toByte()
            }
            data
        } catch (e: Exception) {
            Log.e("FileUtils", "Error al convertir hexString a ByteArray: ${e.message}")
            writeLog(context, "Error al convertir hexString a ByteArray: ${e.message}")
            null
        }
    }
}
