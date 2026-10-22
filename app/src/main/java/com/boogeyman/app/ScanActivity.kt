package com.boogeyman.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.ResultPoint
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory

// Own QR scanner screen, always portrait (set in the manifest)
class ScanActivity : AppCompatActivity() {
    private lateinit var scanner: DecoratedBarcodeView
    private var done = false

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) scanner.resume() else finish()
    }

    private fun hasCam() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        scanner = DecoratedBarcodeView(this)
        setContentView(scanner)
        scanner.setStatusText("Scan the QR on the other phone")
        scanner.barcodeView.decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
        scanner.decodeContinuous(object : BarcodeCallback {
            override fun barcodeResult(r: BarcodeResult) {
                if (done || r.text.isNullOrEmpty()) return
                done = true
                setResult(RESULT_OK, Intent().putExtra("qr", r.text))
                finish()
            }
            override fun possibleResultPoints(p: MutableList<ResultPoint>?) {}
        })
        if (!hasCam()) permLauncher.launch(Manifest.permission.CAMERA)
    }

    override fun onResume() { super.onResume(); if (hasCam()) scanner.resume() }
    override fun onPause() { super.onPause(); scanner.pause() }
}
