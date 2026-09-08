package StarBase.Android.Forum.net

import okhttp3.RequestBody
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer

internal class UploadBody(
    private val body: RequestBody,
    private val onProgress: (Long, Long) -> Unit
) : RequestBody() {
    override fun contentType() = body.contentType()
    override fun contentLength() = body.contentLength()
    override fun isOneShot() = true

    override fun writeTo(sink: BufferedSink) {
        val total = contentLength()
        var written = 0L
        onProgress(0, total)
        val forwarding = object : ForwardingSink(sink) {
            override fun write(source: okio.Buffer, byteCount: Long) {
                super.write(source, byteCount)
                written += byteCount
                onProgress(written, total)
            }
        }.buffer()
        body.writeTo(forwarding)
        forwarding.flush()
    }
}
