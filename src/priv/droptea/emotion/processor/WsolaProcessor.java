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


package priv.droptea.emotion.processor;

import priv.droptea.emotion.AudioDispatcher;
import priv.droptea.emotion.AudioEvent;

/**
 *
 * <p>
 * An overlap-add technique based on waveform similarity (WSOLA) for high
 * quality time-scale modification of speech
 * </p>
 * <p>
 * A concept of waveform similarity for tackling the problem of time-scale
 * modification of speech is proposed. It is worked out in the context of
 * short-time Fourier transform representations. The resulting WSOLA
 * (waveform-similarity-based synchronized overlap-add) algorithm produces
 * high-quality speech output, is algorithmically and computationally efficient
 * and robust, and allows for online processing with arbitrary time-scaling
 * factors that may be specified in a time-varying fashion and can be chosen
 * over a wide continuous range of values.
 * </p>
 * <p>
 * Inspired by the work soundtouch by Olli Parviainen,
 * http://www.surina.net/soundtouch, especially the TDStrech.cpp file.
 * </p>
 * @author Joren Six
 * @author Olli Parviainen
 */
public class WsolaProcessor implements AudioProcessor {	
	//搜索窗长度
	private int seekWindowLength;
	//搜索窗可移动距离长度
	private int seekWindowMoveLength;
	//叠加区域长度
	private int overlapLength;
	//该数组保存的是上一块音频数据末尾的overlapLegnth长度的数据，用于对下一块音频数据进行波形相似查找，找到后进行叠加输出
	private float[] pMidBuffer;	
	private float[] pRefMidBuffer;
	private float[] compositeFrameBuffer;
	//分析帧的长度
	private int analysisFrameLength;
	//有效帧长度，有效帧是分析帧中的有效数据
	private int effectiveFrameLengthInAnalysisFrame;
	//分析帧中重复上一帧数据的长度
	private int duplicateLengthInAnalysisFrame;
	//表示播放速度，tempo大于1时音频加快播放，也就是删掉一些音频数据；tempo小于1时语速减慢，也就是重叠一些音频数据
	private double tempo;
	//用于切分输入音频数据并分发音频块的分发器
	private AudioDispatcher dispatcher;

	private Parameters newParameters;
	
	/**
	 * Create a new instance based on algorithm parameters for a certain audio format.
	 * @param params The parameters for the algorithm.
	 */
	public WsolaProcessor(Parameters  params){
		setParameters(params);
		applyNewParameters();
	}
	
	public void setParameters(Parameters params){
		newParameters = params;
	}
	public Parameters getParameters() {
		return newParameters;
	}
	
	public void setDispatcher(AudioDispatcher newDispatcher){
		this.dispatcher = newDispatcher;
	}
	
	private void applyNewParameters(){
		Parameters params = newParameters;
		int oldOverlapLength = overlapLength;
		overlapLength = (int) ((params.getSampleRate() * params.getOverlapMs())/1000);
		seekWindowLength = (int) ((params.getSampleRate() * params.getSeekWindowMs())/1000);
		seekWindowMoveLength = (int) ((params.getSampleRate() *  params.getSeekWindowMoveMs())/1000);
		tempo = params.getTempo();
		
		//pMidBuffer and pRefBuffer are initialized with 8 times the needed length to prevent a reset
		//of the arrays when overlapLength changes.
		if(overlapLength > oldOverlapLength * 8 && pMidBuffer==null){
			pMidBuffer = new float[overlapLength * 8]; //overlapLengthx2?
			pRefMidBuffer = new float[overlapLength * 8];//overlapLengthx2?
			System.out.println("New overlapLength" + overlapLength);
		}
		//这里是在根据tempo的值来为AudioDispatcher定义有效帧effectiveFrameLengthInAnalysisFrame的长度。
		//由两个公式：合成帧=搜索窗-重叠区域，播放速度=有效帧/合成帧,得到有效帧=播放速度*(搜索窗-重叠区域)，于是就有了下面的公式
		effectiveFrameLengthInAnalysisFrame = (int)Math.ceil(tempo * (seekWindowLength - overlapLength));
		//分析帧必须要比搜索窗+搜索窗可移动距离大
		analysisFrameLength = Math.max(effectiveFrameLengthInAnalysisFrame + overlapLength, seekWindowLength) + seekWindowMoveLength;
		duplicateLengthInAnalysisFrame = analysisFrameLength-effectiveFrameLengthInAnalysisFrame;
		
		float[] prevCompositeFrameBuffer = compositeFrameBuffer;
		compositeFrameBuffer = new float[getCompositeFrameLength()];
		if(prevCompositeFrameBuffer!=null){
			System.out.println("Copy outputFloatBuffer contents");
			for(int i = 0 ; i < prevCompositeFrameBuffer.length && i < compositeFrameBuffer.length ; i++){
				compositeFrameBuffer[i] = prevCompositeFrameBuffer[i];
			}
		}
		
		newParameters = null;
	}
	
	public int getAnalysisFrameLength(){
		return analysisFrameLength;
	}
	
	public int getDuplicateLengthInAnalysisFrame(){
		return duplicateLengthInAnalysisFrame;
	}
	//获取合成帧长度
	private int getCompositeFrameLength(){
		return seekWindowLength - overlapLength;
	}
	
	
	
	
	/**
	 * Overlaps the sample in output with the samples in input.
	 * @param output The output buffer.
	 * @param input The input buffer.
	 */
	private void overlap(final float[] output, int outputOffset, float[] input,int inputOffset){
		for(int i = 0 ; i < overlapLength ; i++){
			int itemp = overlapLength - i;
			output[i + outputOffset] = (input[i + inputOffset] * i + pMidBuffer[i] * itemp ) / overlapLength;  
		}
	}
	
	
	/**
	 * Seeks for the optimal overlap-mixing position.
	 * 
	 * The best position is determined as the position where the two overlapped
	 * sample sequences are 'most alike', in terms of the highest
	 * cross-correlation value over the overlapping period
	 * 
	 * @param inputBuffer The input buffer
	 * @param postion The position where to start the seek operation, in the input buffer. 
	 * @return The best position.
	 */
	private int seekBestOverlapPosition(float[] inputBuffer, int postion) {
		int bestOffset;
		double bestCorrelation, currentCorrelation;
		int tempOffset;

		int comparePosition;

		// Slopes the amplitude of the 'midBuffer' samples
		precalcCorrReferenceMono();

		bestCorrelation = -10;
		bestOffset = 0;

		// Scans for the best correlation value by testing each possible
		// position
		// over the permitted range.
		for (tempOffset = 0; tempOffset < seekWindowMoveLength; tempOffset++) {

			comparePosition = postion + tempOffset;
			//计算两个波形的相似度
			// Calculates correlation value for the mixing position
			// corresponding
			// to 'tempOffset'
			currentCorrelation = (double) calcCrossCorr(pRefMidBuffer, inputBuffer,comparePosition);
			//中间的位置拥有更大的权重
			// heuristic rule to slightly favor values close to mid of the range
			double tmp = (double) (2 * tempOffset - seekWindowMoveLength) / seekWindowMoveLength;
			currentCorrelation = ((currentCorrelation + 0.1) * (1.0 - 0.25 * tmp * tmp));
			// Checks for the highest correlation value
			if (currentCorrelation > bestCorrelation) {
				bestCorrelation = currentCorrelation;
				bestOffset = tempOffset;
			}
		}

		return bestOffset;

	}
	
	/**
	* Slopes the amplitude of the 'midBuffer' samples so that cross correlation
	* is faster to calculate. Why is this faster?
	*/
	void precalcCorrReferenceMono()
	{
	    for (int i = 0; i < overlapLength; i++){
	    	float temp = i * (overlapLength - i);
	        pRefMidBuffer[i] = pMidBuffer[i] * temp;
	    }
	}	

	
	double calcCrossCorr(float[] mixingPos, float[] compare, int offset){
		double corr = 0;
	    double norm = 0;
	    for (int i = 1; i < overlapLength; i ++){
	        corr += mixingPos[i] * compare[i + offset];
	        norm += mixingPos[i] * mixingPos[i];
	    }
	    // To avoid division by zero.
	    if (norm < 1e-8){
	    	norm = 1.0;    
	    }
	    return corr / Math.pow(norm,0.5);
	}
	
	
	@Override
	public boolean process(AudioEvent audioEvent) {
		float[] audioFloatBuffer = audioEvent.getFloatBuffer();
		assert audioFloatBuffer.length == getAnalysisFrameLength();
		//用上一个搜索窗尾部重叠区域大小的数据（波形数据）作为参考，从当前分析帧头部开始往后平移寻找最相似的数据（相似的波形数据），返回平移的距离
		int offset =  seekBestOverlapPosition(audioFloatBuffer,0);
		//把两个相似波形叠加并添加到合成帧数组的开头
		overlap(compositeFrameBuffer,0,audioFloatBuffer,offset);
		//把搜索窗中的非重叠区域直接加到合成帧组成完整的合成帧
		int notOverlapLength = seekWindowLength - 2 * overlapLength;
		System.arraycopy(audioFloatBuffer, offset + overlapLength, compositeFrameBuffer, overlapLength, notOverlapLength);
	    //保存搜索窗尾部重叠区域大小的数据到pMidBuffer数组里，用于下一个分析帧进行相似波形匹配
		System.arraycopy(audioFloatBuffer, offset + notOverlapLength + overlapLength, pMidBuffer, 0, overlapLength);
		
		assert compositeFrameBuffer.length == getCompositeFrameLength();
		
		audioEvent.setFloatBuffer(compositeFrameBuffer);
		audioEvent.setOverlap(0);
		float[] copyBuffer = new float[compositeFrameBuffer.length];
		System.arraycopy(compositeFrameBuffer,0, copyBuffer,0 ,compositeFrameBuffer.length);
		audioEvent.getDataForAnalysisInWaveformChart().setFloatBufferWsola(copyBuffer);
		audioEvent.getDataForAnalysisInWaveformChart().setSeekWinOffsetWsola(offset);
		audioEvent.getDataForAnalysisInWaveformChart().setSeekWindowMoveLengthWsola(seekWindowMoveLength);
		audioEvent.getDataForAnalysisInWaveformChart().setOverlapWsola(overlapLength);
		audioEvent.getDataForAnalysisInWaveformChart().setDataNotOverlapWsola(compositeFrameBuffer.length-overlapLength);
		if(newParameters!=null){
			applyNewParameters();
			dispatcher.setStepSizeAndOverlap(getAnalysisFrameLength(),getDuplicateLengthInAnalysisFrame());
		}
		
		return true;
	}

	@Override
	public void processingFinished() {
		// NOOP
	}


	
	/**
	 * An object to encapsulate some of the parameters for
	 *         WSOLA, together with a couple of practical helper functions.
	 * 
	 * @author Joren Six
	 */
	public static class Parameters {
		//sequenceMs个毫秒的采样数据作为搜索窗的长度
		private final int seekWindowMs;
		//seekWindowMoveMs个毫秒的采样数据作为搜索窗可移动的距离长度
		private final int seekWindowMoveMs;
		//overlapMs个毫秒的采样数据个数作为叠加区域长度
		private final int overlapMs;
		//表示播放速度，tempo大于1时音频加快播放，也就是删掉一些音频数据；tempo小于1时语速减慢，也就是重叠一些音频数据
		private final double tempo;
		//表示音频采样率，例如44100.0
		private final double sampleRate;

		public Parameters(double tempo, double sampleRate, int seekWindowMs, int seekWindowMoveMs, int overlapMs) {
			this.tempo = tempo;
			this.sampleRate = sampleRate;
			this.overlapMs = overlapMs;
			this.seekWindowMoveMs = seekWindowMoveMs;
			this.seekWindowMs = seekWindowMs;
		}
		
		public static Parameters speechDefaults(double tempo, double sampleRate){
			int seekWindowMs = 40;//40
			int seekWindowMoveMs = 15;//15
			int overlapMs = 12;//12
			return new Parameters(tempo,sampleRate,seekWindowMs, seekWindowMoveMs,overlapMs);
		}
		
		public static Parameters musicDefaults(double tempo, double sampleRate){
			int seekWindowMs = 82;
			int seekWindowMoveMs =  28;
			int overlapMs = 12;
			return new Parameters(tempo,sampleRate,seekWindowMs, seekWindowMoveMs,overlapMs);
		}
		
		public static Parameters slowdownDefaults(double tempo, double sampleRate){
			int seekWindowMs = 100;
			int seekWindowMoveMs =  35;
			int overlapMs = 20;
			return new Parameters(tempo,sampleRate,seekWindowMs, seekWindowMoveMs,overlapMs);
		}
		
		public static Parameters automaticDefaults(double tempo, double sampleRate){
			double tempoLow = 0.5; // -50% speed
			double tempoHigh = 2.0; // +100% speed
			
			double sequenceMsLow = 125; //ms
			double sequenceMsHigh = 50; //ms
			double sequenceK = ((sequenceMsHigh - sequenceMsLow) / (tempoHigh - tempoLow));
			double sequenceC = sequenceMsLow - sequenceK * tempoLow;
			
			double seekLow = 25;// ms
			double seekHigh = 15;// ms
			double seekK =((seekHigh - seekLow) / (tempoHigh-tempoLow));
			double seekC = seekLow - seekK * seekLow;
			
			int seekWindowMs = (int) (sequenceC + sequenceK * tempo + 0.5);
			int seekWindowMoveMs =  (int) (seekC + seekK * tempo + 0.5);
			int overlapMs = 12;
			return new Parameters(tempo,sampleRate,seekWindowMs, seekWindowMoveMs,overlapMs);
		}

		public double getOverlapMs() {
			return overlapMs;
		}

		public double getSeekWindowMs() {
			return seekWindowMs;
		}

		public double getSeekWindowMoveMs() {
			return seekWindowMoveMs;
		}
		
		public double getSampleRate() {
			return sampleRate;
		}
		
		public double getTempo(){
			return tempo;
		}
	}
}
