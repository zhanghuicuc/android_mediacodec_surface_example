package com.example.zhanghui.decode2surfaceexample;

import android.app.Activity;
import android.content.Intent;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.Trace;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.io.IOException;
import java.nio.ByteBuffer;

public class TextureViewActivity extends Activity implements TextureView.SurfaceTextureListener{

    private static final String TAG = "TextureViewActivity";
    private static final int STATE_IDLE = 1;
    private static final int STATE_PREPARING = 2;
    private static final int STATE_PLAYING = 3;
    private static final int STATE_STOPPED = 4;
    private static final int STATE_RELEASED = 5;

    private TextureView mTextureV;
    private Surface mSurface;

    private static final MediaCodecList sMCL = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
    private String decoder = null;
    MediaExtractor extractor;
    MediaCodec codec;
    private Integer mState = STATE_IDLE;

    private final long kTimeOutUs = 5000; // 5ms timeout
    private boolean sawInputEOS = false;
    private boolean sawOutputEOS = false;
    private int deadDecoderCounter = 0;
    private int samplenum = 0;
    private int numframes = 0;
    private String fileUrl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_glplayer);

        mTextureV = new TextureView(this);
        mTextureV.setSurfaceTextureListener(this);
        ((FrameLayout) findViewById(R.id.root)).addView(mTextureV);

        Intent intent = getIntent();
        fileUrl = intent.getStringExtra("fileurl");

        Intent i = new Intent("com.android.music.musicservicecommand");
        i.putExtra("command", "pause");
        this.sendBroadcast(i);
    }


    public class DecodeTask extends AsyncTask<String, Integer, String> {

        @Override
        protected String doInBackground(String... url) {
            DecodeVideo(url[0]);
            return url[0];
        }

        @Override
        protected void onPostExecute(String s) {
        }
    }

    private void DecodeVideo(String fileUrl){
        mState = STATE_IDLE;
        extractor = new MediaExtractor();
        codec = null;
        synchronized (mState) {
            mState = STATE_PREPARING;
        }
        if (!canDecodeVideo(fileUrl, extractor)) {
            Log.i(TAG, "no supported decoder found ");
            return; //skip
        }
        int trackIndex = extractor.getSampleTrackIndex();
        MediaFormat format = extractor.getTrackFormat(trackIndex);
        try {
            codec = MediaCodec.createByCodecName(decoder);
        } catch (IOException e) {
            Log.e(TAG, "failed to create decoder");
            return;
        }
        Log.i("@@@@", "using codec: " + codec.getName());
        codec.configure(format, mSurface, null /* crypto */, 0 /* flags */);
        codec.start();
        synchronized (mState) {
            mState = STATE_PLAYING;
        }

        long decodeStartTime = SystemClock.elapsedRealtime();
        doSomeWork();
        long decodeEndTime = SystemClock.elapsedRealtime();

        synchronized (mState) {
            if (mState == STATE_PLAYING) {
                codec.stop();
                mState = STATE_STOPPED;
                codec.release();
                mState = STATE_RELEASED;
                extractor.release();
                mState = STATE_IDLE;
            }
        }
        return;
    }

    private void doSomeWork() {
        // start decode loop
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        final long kTimeOutUs = 5000; // 5ms timeout
        boolean sawInputEOS = false;
        boolean sawOutputEOS = false;
        int deadDecoderCounter = 0;
        int samplenum = 0;
        int numframes = 0;
        while (!sawOutputEOS && deadDecoderCounter < 100 && mState == STATE_PLAYING) {
            // handle input
            Trace.beginSection("DecodeVideo handleinput");
            if (!sawInputEOS) {
                synchronized (mState) {
                    if (mState == STATE_PLAYING) {
                        int inputBufIndex = codec.dequeueInputBuffer(kTimeOutUs);

                        if (inputBufIndex >= 0) {
                            ByteBuffer dstBuf = codec.getInputBuffer(inputBufIndex);

                            int sampleSize =
                                    extractor.readSampleData(dstBuf, 0 /* offset */);
                            long presentationTimeUs = extractor.getSampleTime();
                            Log.i("@@@@", "read sample " + samplenum + ":" + extractor.getSampleFlags()
                                    + " @ " + extractor.getSampleTime() + " size " + sampleSize);

                            if (sampleSize < 0) {
                                Log.d(TAG, "saw input EOS.");
                                sawInputEOS = true;
                                sampleSize = 0; // required otherwise queueInputBuffer returns invalid.
                            } else {
                                samplenum++; // increment before comparing with stopAtSample
                                if (samplenum == -1) {
                                    Log.d(TAG, "saw input EOS (stop at sample).");
                                    sawInputEOS = true; // tag this sample as EOS
                                }
                            }
                            codec.queueInputBuffer(
                                    inputBufIndex,
                                    0 /* offset */,
                                    sampleSize,
                                    presentationTimeUs,
                                    sawInputEOS ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);

                            if (!sawInputEOS) {
                                extractor.advance();
                            }
                        }
                    }
                }
            }
            Trace.endSection();

            synchronized (mState) {
                if (mState == STATE_PLAYING) {
                    // handle output
                    int outputBufIndex = codec.dequeueOutputBuffer(info, kTimeOutUs);

                    deadDecoderCounter++;
                    Trace.beginSection("DecodeVideo handleoutput");
                    if (outputBufIndex >= 0) {
                        if (info.size > 0) { // Disregard 0-sized buffers at the end.
                            deadDecoderCounter = 0;
                            numframes++;
                            Log.d(TAG, "got frame, size " + info.size + "/" + info.presentationTimeUs +
                                    "/" + numframes + "/" + info.flags);
                        }
                        codec.releaseOutputBuffer(outputBufIndex, true /* render */);
                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            Log.d(TAG, "saw output EOS.");
                            sawOutputEOS = true;
                        }
                    } else if (outputBufIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            /*MediaFormat oformat = codec.getOutputFormat();
                            if (oformat.containsKey(MediaFormat.KEY_COLOR_FORMAT) &&
                                    oformat.containsKey(MediaFormat.KEY_WIDTH) &&
                                    oformat.containsKey(MediaFormat.KEY_HEIGHT)) {
                                int colorFormat = oformat.getInteger(MediaFormat.KEY_COLOR_FORMAT);
                                int width = oformat.getInteger(MediaFormat.KEY_WIDTH);
                                int height = oformat.getInteger(MediaFormat.KEY_HEIGHT);
                                Message msg = new Message();
                                msg.what = FORMAT_RESOLUTION_GOT;
                                msg.arg1 = width;
                                msg.arg2 = height;
                                mainHandler.sendMessage(msg);
                                Log.d(TAG, "output fmt: " + colorFormat + " dim " + width + "x" + height);
                            }*/
                    } else if (outputBufIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        Log.d(TAG, "no output frame available yet");
                    }
                }
            }
            Trace.endSection();
        }
    }

    private boolean canDecodeVideo(String fileUrl, MediaExtractor ex) {
        try {
            ex.setDataSource(fileUrl);
            for (int i = 0; i < ex.getTrackCount(); ++i) {
                MediaFormat format = ex.getTrackFormat(i); //ex. call MPEG4Extractor.getTrackMetaData
                // only check for video codecs
                String mime = format.getString(MediaFormat.KEY_MIME).toLowerCase();
                if (!mime.startsWith("video/")) {
                    continue;
                }
                decoder = sMCL.findDecoderForFormat(format);
                if (decoder != null) {
                    ex.selectTrack(i);
                    return true;
                }
            }
        } catch (IOException e) {
            Log.i(TAG, "could not open path " + fileUrl);
        }
        return false;
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
        // There's a short delay between the start of the activity and the initialization
        // of the SurfaceTexture that backs the TextureView.  We don't want to try to
        // send a video stream to the TextureView before it has initialized
        Log.d(TAG, "SurfaceTexture ready (" + width + "x" + height + ")");
        mSurface = new Surface(mTextureV.getSurfaceTexture());
        new DecodeTask().execute(fileUrl);
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) {
        // ignore
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        if (codec != null) {
            Log.e(TAG, "call surfaceDestroyed");
            synchronized (mState) {
                if (mState == STATE_PLAYING) {
                    Log.e(TAG, "call codec stop");
                    codec.stop();
                    codec.release();
                    extractor.release();
                } else if (mState == STATE_STOPPED) {
                    Log.e(TAG, "call codec release");
                    codec.release();
                    extractor.release();
                } else if (mState == STATE_RELEASED) {
                    extractor.release();
                }
                mSurface = null;
                mState = STATE_IDLE;
            }
        }
        return true;    // caller should release ST
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        Log.e(TAG, "onSurfaceTextureUpdated called");
        // ignore
    }
}
