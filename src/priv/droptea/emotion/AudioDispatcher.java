/*
*      _______                       _____   _____ _____  
*     |__   __|                     |  __ \ / ____|  __ \ 
*        | | __ _ _ __ ___  ___  ___| |  | | (___ | |__) |
*        | |/ _` | '__/ __|/ _ \/ __| |  | |\___ \|  ___/ 
*        | | (_| | |  \__ \ (_) \__ \ |__| |____) | |     
*        |_|\__,_|_|  |___/\___/|___/_____/|_____/|_|     
*                                                         
* -------------------------------------------------------------
*
* TarsosDSP is developed by Joren Six at IPEM, University Ghent
*  
* -------------------------------------------------------------
*
*  Info: http://0110.be/tag/TarsosDSP
*  Github: https://github.com/JorenSix/TarsosDSP
*  Releases: http://0110.be/releases/TarsosDSP/
*  
*  TarsosDSP includes modified source code by various authors,
*  for credits and info, see README.
* 
*/


package priv.droptea.emotion;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

import priv.droptea.emotion.AudioEvent.DataForAnalysisInWaveformChart;
import priv.droptea.emotion.io.TarsosDSPAudioFloatConverter;
import priv.droptea.emotion.io.TarsosDSPAudioFormat;
import priv.droptea.emotion.io.TarsosDSPAudioInputStream;
import priv.droptea.emotion.processor.AudioProcessor;



/**
 * This class plays a file and sends float arrays to registered AudioProcessor
 * implementors. This class can be used to feed FFT's, pitch detectors, audio players, ...
 * Using a (blocking) audio player it is even possible to synchronize execution of
 * AudioProcessors and sound. This behavior can be used for visualization.
 * @author Joren Six
 * 每次读取一块音频数据，并把这块数据发给各个自定义的音频处理器进行处理。
 */
public class AudioDispatcher implements Runnable {

	/**
	 * Log messages.
	 */
	private static final Logger LOG = Logger.getLogger(AudioDispatcher.class.getName());

	/**
	 * The audio stream (in bytes), conversion to float happens at the last
	 * moment.
	 */
	private final TarsosDSPAudioInputStream audioInputStream;

	/**
	 * This buffer is reused again and again to store audio data using the float
	 * data type.
	 * 用于存放一块音频数据的float数组
	 */
	private float[] audioFloatBuffer;

	/**
	 * This buffer is reused again and again to store audio data using the byte
	 * data type.
	 * 用于存放一块音频数据的Byte数组，每次都会把这个数组分发给各个音频处理器
	 */
	private byte[] audioByteBuffer;

	/**
	 * A list of registered audio processors. The audio processors are
	 * responsible for actually doing the digital signal processing
	 */
	private final List<AudioProcessor> audioProcessors;

	/**
	 * Converter converts an array of floats to an array of bytes (and vice
	 * versa).
	 */
	private final TarsosDSPAudioFloatConverter converter;
	
	private final TarsosDSPAudioFormat format;

	/**
	 * The floatOverlap: the number of elements that are copied in the buffer
	 * from the previous buffer. Overlap should be smaller (strict) than the
	 * buffer size and can be zero. Defined in number of samples.
	 * floatOverlap:L计算相关性所采用的重叠相加的长度
	 * floatStepSize:Sa分析位移
	 */
	private int floatOverlap, floatStepSize;

	/**
	 * The overlap and stepsize defined not in samples but in bytes. So it
	 * depends on the bit depth. Since the int datatype is used only 8,16,24,...
	 * bits or 1,2,3,... bytes are supported.
	 * byteOverlap:L计算相关性所采用的重叠相加的长度
	 * byteStepSize:Sa分析位移
	 */
	private int byteOverlap, byteStepSize;
	
	
	/**
	 * The number of bytes to skip before processing starts.
	 */
	private long bytesToSkip;
	
	/**
	 * Position in the stream in bytes. e.g. if 44100 bytes are processed and 16
	 * bits per frame are used then you are 0.5 seconds into the stream.
	 * 在流中的位置
	 */
	private long bytesProcessed;
	
	
	/**
	 * The audio event that is send through the processing chain.
	 */
	private AudioEvent audioEvent;
	
	/**
	 * If true the dispatcher stops dispatching audio.
	 */
	private boolean stopped;
	
	/**
	 * If true then the first buffer is only filled up to buffer size - hop size
	 * E.g. if the buffer is 2048 and the hop size is 48 then you get 2000 times
	 * zero 0 and 48 actual audio samples. During the next iteration you get
	 * mostly zeros and 96 samples.
	 */
	private boolean zeroPadFirstBuffer;
	
	/**
	 * If true then the last buffer is zero padded. Otherwise the buffer is
	 * shortened to the remaining number of samples. If false then the audio
	 * processors must be prepared to handle shorter audio buffers.
	 */
	private boolean zeroPadLastBuffer;

	private int seekLength;

	/**
	 * Create a new dispatcher from a stream.
	 * 
	 * @param stream
	 *            The stream to read data from.
	 * @param audioBufferSize
	 *            The size of the buffer defines how much samples are processed
	 *            in one step. Common values are 1024,2048.
	 * @param bufferOverlap
	 *            How much consecutive buffers overlap (in samples). Half of the
	 *            AudioBufferSize is common (512, 1024) for an FFT.
	 */
	public AudioDispatcher(final TarsosDSPAudioInputStream stream, final int audioBufferSize, final int bufferOverlap){
		// The copy on write list allows concurrent modification of the list while
		// it is iterated. A nice feature to have when adding AudioProcessors while
		// the AudioDispatcher is running.
		audioProcessors = new CopyOnWriteArrayList<AudioProcessor>();
		audioInputStream = stream;

		format = audioInputStream.getFormat();
		
			
		setStepSizeAndOverlap(audioBufferSize, bufferOverlap);
		
		audioEvent = new AudioEvent(format);
		audioEvent.setFloatBuffer(audioFloatBuffer);
		audioEvent.setOverlap(bufferOverlap);
		
		converter = TarsosDSPAudioFloatConverter.getConverter(format);
		
		stopped = false;
		
		bytesToSkip = 0;
		
		zeroPadLastBuffer = true;
	}	
	
	/**
	 * Skip a number of seconds before processing the stream.
	 * @param seconds
	 */
	public void skip(double seconds){
		bytesToSkip = Math.round(seconds * format.getSampleRate()) * format.getFrameSize(); 
	}
	
	/**
	 * Set a new step size and overlap size. Both in number of samples. Watch
	 * out with this method: it should be called after a batch of samples is
	 * processed, not during.
	 * 
	 * @param audioBufferSize
	 *            The size of the buffer defines how much samples are processed
	 *            in one step. Common values are 1024,2048.
	 * @param bufferOverlap
	 *            How much consecutive buffers overlap (in samples). Half of the
	 *            AudioBufferSize is common (512, 1024) for an FFT.
	 */
	public void setStepSizeAndOverlap(final int audioBufferSize, final int bufferOverlap){
		audioFloatBuffer = new float[audioBufferSize];
		floatOverlap = bufferOverlap;
		floatStepSize = audioFloatBuffer.length - floatOverlap;

		audioByteBuffer = new byte[audioFloatBuffer.length * format.getFrameSize()];
		byteOverlap = floatOverlap * format.getFrameSize();
		byteStepSize = floatStepSize * format.getFrameSize();
	}
	
	/**
	 * if zero pad is true then the first buffer is only filled up to  buffer size - hop size
	 * E.g. if the buffer is 2048 and the hop size is 48 then you get 2000x0 and 48 filled audio samples
	 * @param zeroPadFirstBuffer true if the buffer should be zeroPadFirstBuffer, false otherwise.
	 */
	public void setZeroPadFirstBuffer(boolean zeroPadFirstBuffer){
		this.zeroPadFirstBuffer = zeroPadFirstBuffer;		
	}
	
	/**
	 * If zero pad last buffer is true then the last buffer is filled with zeros until the normal amount
	 * of elements are present in the buffer. Otherwise the buffer only contains the last elements and no zeros.
	 * By default it is set to true.
	 * 
	 * @param zeroPadLastBuffer
	 */
	public void setZeroPadLastBuffer(boolean zeroPadLastBuffer) {
		this.zeroPadLastBuffer = zeroPadLastBuffer;
	}
	

	/**
	 * Adds an AudioProcessor to the chain of processors.
	 * 
	 * @param audioProcessor
	 *            The AudioProcessor to add.
	 */
	public void addAudioProcessor(final AudioProcessor audioProcessor) {
		audioProcessors.add(audioProcessor);
		LOG.fine("Added an audioprocessor to the list of processors: " + audioProcessor.toString());
	}
	
	/**
	 * Removes an AudioProcessor to the chain of processors and calls its <code>processingFinished</code> method.
	 * 
	 * @param audioProcessor
	 *            The AudioProcessor to remove.
	 */
	public void removeAudioProcessor(final AudioProcessor audioProcessor) {
		audioProcessors.remove(audioProcessor);
		audioProcessor.processingFinished();
		LOG.fine("Remove an audioprocessor to the list of processors: " + audioProcessor.toString());
	}

	public void run() {
		
		int bytesRead = 0;
		
		if(bytesToSkip!=0){
			skipToStart();
		}
	
		//Read the first (and in some cases last) audio block.
		try {
			//needed to get correct time info when skipping first x seconds
			audioEvent.setBytesProcessed(bytesProcessed);
			bytesRead = readNextAudioBlock();
		} catch (IOException e) {
			String message="Error while reading audio input stream: " + e.getMessage();	
			LOG.warning(message);
			throw new Error(message);
		}

		// As long as the stream has not ended
		while (bytesRead != 0 && !stopped) {
			
			//Makes sure the right buffers are processed, they can be changed by audio processors.
			//将切分好的音频数据分发给各个自定义的音频处理器进行实时处理
			for (final AudioProcessor processor : audioProcessors) {
				if(!processor.process(audioEvent)){
					//skip to the next audio processors if false is returned.
					break;
				}	
			}
			
			if(!stopped){			
				//Update the number of bytes processed;
				bytesProcessed += bytesRead;
				
					
				// Read, convert and process consecutive overlapping buffers.
				// Slide the buffer.
				try {
					audioEvent.setBytesProcessed(bytesProcessed);
					bytesRead = readNextAudioBlock();
					//audioEvent.setOverlap(floatOverlap);
				} catch (IOException e) {
					String message="Error while reading audio input stream: " + e.getMessage();	
					LOG.warning(message);
					throw new Error(message);
				}
			}
		}

		// Notify all processors that no more data is available. 
		// when stop() is called processingFinished is called explicitly, no need to do this again.
		// The explicit call is to prevent timing issues.
		if(!stopped){
			stop();
		}
	}
	
	
	private void skipToStart() {
		long skipped = 0l;
		try{
			skipped = audioInputStream.skip(bytesToSkip);
			if(skipped !=bytesToSkip){
				throw new IOException();
			}
			bytesProcessed += bytesToSkip;
		}catch(IOException e){
			String message=String.format("Did not skip the expected amount of bytes,  %d skipped, %d expected!", skipped,bytesToSkip);	
			LOG.warning(message);
			throw new Error(message);
		}
	}

	/**
	 * Stops dispatching audio data.
	 */
	public void stop() {
		stopped = true;
		for (final AudioProcessor processor : audioProcessors) {
			processor.processingFinished();
		}
		try {
			audioInputStream.close();
		} catch (IOException e) {
			LOG.log(Level.SEVERE, "Closing audio stream error.", e);
		}
	}

	/**
	 * Reads the next audio block. It tries to read the number of bytes defined
	 * by the audio buffer size minus the overlap. If the expected number of
	 * bytes could not be read either the end of the stream is reached or
	 * something went wrong.
	 * 
	 * The behavior for the first and last buffer is defined by their corresponding the zero pad settings. The method also handles the case if
	 * the first buffer is also the last.
	 * 块数据分为两部分，前部分是重叠区域（是上一块数据的末尾部分），后部分是从音频源里新取出来的数据。
	 * 第一块数据比较特殊，第一块没有重叠区域，整块都是从音频源里取出来的
	 * 这是获取一块音频数据的方法，这个方法会被循环调用，通过这个方法我们可以一块块的读取出音频数据，直到读取出所有音频数据后才会被停止调用。
	 * 从音频源中读取数据，并根据设置的重叠区域大小进行处理后得到一块音频数据，这块数据会发给各个自定义的音频处理器进行处理
	 * @return The number of bytes read.读取了音频源的数据大小（第一次读取的返回值就是块大小，之后读取的返回值是块大小减去重叠区域大小，最后一次读取的返回值是...）
	 * @throws IOException
	 *             When something goes wrong while reading the stream. In
	 *             particular, an IOException is thrown if the input stream has
	 *             been closed.
	 */
	private int readNextAudioBlock() throws IOException {
		assert floatOverlap < audioFloatBuffer.length;
		// Is this the first buffer?
		//是否是第一块数据
		boolean isFirstBuffer = (bytesProcessed ==0 || bytesProcessed == bytesToSkip);
		//Shift the audio information using array copy since it is probably faster than manually shifting it.
		// No need to do this on the first buffer
		//如果不是第一块数据，会根据重叠区域大小，把上一块数据的末尾部分作为本块数据的头部进行填充，这部分数据作为重叠区域数据。
		if(!isFirstBuffer && audioFloatBuffer.length == floatOverlap + floatStepSize ){
			System.arraycopy(audioFloatBuffer,floatStepSize, audioFloatBuffer,0 ,floatOverlap);
			/*
			for(int i = floatStepSize ; i < floatStepSize+floatOverlap ; i++){
				audioFloatBuffer[i-floatStepSize] = audioFloatBuffer[i];
			}*/
		}
		
		final int offsetInBytes;
		final int offsetInSamples;
		final int bytesToRead;
		//Determine the amount of bytes to read from the stream
		//下面是从音频源中读取数据填充到块中的操作
		//因为第一块数据，没有填充重叠区域，所以需要从音频源中读取出块大小的数据，
		//如果不是第一块数据，只需要读取出块大小减去重叠区域大小的数据即可
		if(isFirstBuffer && !zeroPadFirstBuffer){
			//If this is the first buffer and we do not want to zero pad the
			//first buffer then read a full buffer
			bytesToRead =  audioByteBuffer.length;
			// With an offset in bytes of zero;
			offsetInBytes = 0;
			offsetInSamples=0;
		}else{
			//In all other cases read the amount of bytes defined by the step size
			bytesToRead = byteStepSize;
			offsetInBytes = byteOverlap;
			offsetInSamples = floatOverlap;
		}
		
		// Total amount of bytes read
		int totalBytesRead = 0;
		// The amount of bytes read from the stream during one iteration.
		//实际在一次读取中获得的数据大小
		int bytesRead=0;
		
		// Is the end of the stream reached?
		//是否音频数据已经读取完成
		boolean endOfStream = false;
				
		// Always try to read the 'bytesToRead' amount of bytes.
		// unless the stream is closed (stopped is true) or no bytes could be read during one iteration 
		//循环读取音频数据，直到填满w数组为止
		while(!stopped && !endOfStream && totalBytesRead<bytesToRead){
			try{
				bytesRead = audioInputStream.read(audioByteBuffer, offsetInBytes + totalBytesRead , bytesToRead - totalBytesRead);
			}catch(IndexOutOfBoundsException e){
				// The pipe decoder generates an out of bounds if end
				// of stream is reached. Ugly hack...
				bytesRead = -1;
			}
			if(bytesRead == -1){
				// The end of the stream is reached if the number of bytes read during this iteration equals -1
				endOfStream = true;
			}else{
				// Otherwise add the number of bytes read to the total 
				totalBytesRead += bytesRead;
			}
		}
		
		if(endOfStream){
			// Could not read a full buffer from the stream, there are two options:
			if(zeroPadLastBuffer){
				//Make sure the last buffer has the same length as all other buffers and pad with zeros
				for(int i = offsetInBytes + totalBytesRead; i < audioByteBuffer.length; i++){
					audioByteBuffer[i] = 0;
				}
				converter.toFloatArray(audioByteBuffer, offsetInBytes, audioFloatBuffer, offsetInSamples, floatStepSize);
			}else{
				// Send a smaller buffer through the chain.
				byte[] audioByteBufferContent = audioByteBuffer;
				audioByteBuffer = new byte[offsetInBytes + totalBytesRead];
				for(int i = 0 ; i < audioByteBuffer.length ; i++){
					audioByteBuffer[i] = audioByteBufferContent[i];
				}
				int totalSamplesRead = totalBytesRead/format.getFrameSize();
				audioFloatBuffer = new float[offsetInSamples + totalBytesRead/format.getFrameSize()];
				converter.toFloatArray(audioByteBuffer, offsetInBytes, audioFloatBuffer, offsetInSamples, totalSamplesRead);
				
				
			}			
		}else if(bytesToRead == totalBytesRead) {
			// The expected amount of bytes have been read from the stream.
			//得到我们期望的数据，把音频的byte数据转成float数据,这会方便后面对音频数据进行分析
			if(isFirstBuffer && !zeroPadFirstBuffer){
				converter.toFloatArray(audioByteBuffer, 0, audioFloatBuffer, 0, audioFloatBuffer.length);
			}else{
				converter.toFloatArray(audioByteBuffer, offsetInBytes, audioFloatBuffer, offsetInSamples, floatStepSize);
			}
		} else if(!stopped) {
			// If the end of the stream has not been reached and the number of bytes read is not the
			// expected amount of bytes, then we are in an invalid state; 
			//跑到这里不是我们希望看到的结果
			throw new IOException(String.format("The end of the audio stream has not been reached and the number of bytes read (%d) is not equal "
					+ "to the expected amount of bytes(%d).", totalBytesRead,bytesToRead));
		}
		
		// Makes sure AudioEvent contains correct info.
		//把float数据和重叠区域大小设置到事件中
		audioEvent.setFloatBuffer(audioFloatBuffer);
		audioEvent.setOverlap(offsetInSamples);
		DataForAnalysisInWaveformChart mDataForAnalysisInWaveformChart = new DataForAnalysisInWaveformChart();
		float[] audioFloatBuffer = audioEvent.getFloatBuffer();
		float[] copyBuffer = new float[audioFloatBuffer.length];
		System.arraycopy(audioFloatBuffer,0, copyBuffer,0 ,audioFloatBuffer.length);
		mDataForAnalysisInWaveformChart.setFloatBufferOriginal(copyBuffer);
		mDataForAnalysisInWaveformChart.setDuplicateLengthInAnalysisFrame(offsetInSamples);
		audioEvent.setDataForAnalysisInWaveformChart(mDataForAnalysisInWaveformChart);
		return totalBytesRead; 
	}
	
	public TarsosDSPAudioFormat getFormat(){
		return format;
	}
	
	/**
	 * 
	 * @return The currently processed number of seconds.
	 */
	public float secondsProcessed(){
		return bytesProcessed / (format.getSampleSizeInBits() / 8) / format.getSampleRate() / format.getChannels() ;
	}

	public void setAudioFloatBuffer(float[] audioBuffer){
		audioFloatBuffer = audioBuffer;
	}
	/**
	 * @return True if the dispatcher is stopped or the end of stream has been reached.
	 */
	public boolean isStopped(){
		return stopped;
	}
	
}
