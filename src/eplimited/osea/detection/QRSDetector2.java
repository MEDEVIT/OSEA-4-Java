/*
 * Copyright (c) 2012 Patrick S. Hamilton (pat@eplimited.com), Wolfgang Halbeisen (halbeisen.wolfgang@gmail.com)
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software 
 * and associated documentation files (the "Software"), to deal in the Software without restriction, 
 * including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, 
 * and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, 
 * subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in all copies 
 * or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, 
 * INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE 
 * AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, 
 * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, 
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package eplimited.osea.detection;

import eplimited.osea.detection.QRSDetectorParameters.PreBlankParameters;

/**
 * This file contains functions for detecting QRS complexes in an ECG.
 * 
 * QRSDet() implements a modified version of the QRS detection
 * algorithm described in:
 * 
 * Hamilton, Tompkins, W. J., "Quantitative investigation of QRS
 * detection rules using the MIT/BIH arrhythmia database",
 * IEEE Trans. Biomed. Eng., BME-33, pp. 1158-1165, 1987.
 * 
 * Consecutive ECG samples are passed to QRSDet.  QRSDet was
 * designed for a 200 Hz sample rate.  QRSDet contains a number
 * of variables that it uses to adapt to different ECG
 * signals. These variables can be reset by creating a new object.
 */
public class QRSDetector2 
	{

	private QRSDetectorParameters qrsDetParas ;
	private PreBlankParameters preBlankParas ;
	private QRSFilterer qrsFilterer ;

	private int det_thresh ;
	private int qpkcnt = 0 ;
	private int[] qrsbuf = new int[8] ;
	private int[] noise = new int[8] ;
	private int[] rrbuf = new int[8] ;
	private int[] rsetBuff = new int[8] ;
	private int rsetCount = 0 ;
	private int nmean ;
	private int qmean ;
	private int rrmean ;
	private int count = 0 ;
	private int sbpeak = 0 ;
	private int sbloc ;
	private int sbcount ;
	private int maxder = 0 ;
	private int initBlank = 0 ;
	private int initMax = 0;
	private int preBlankCnt = 0 ;
	private int tempPeak ;
	/** Buffer holding derivative data. */
	private int[] DDBuffer ;
	private int DDPtr = 0 ;
	private double TH = 0.3125 ;
	private int MEMMOVELEN = 7;
	/** Prevents detections of peaks smaller than 150 uV. */
	private int MIN_PEAK_AMP = 7;
	
	private int Peak_max = 0 ;
	private int Peak_timeSinceMax = 0 ;
	private int Peak_lastDatum ;
	
	/**
	 * Create a new detector with the given parameters.
	 * @param qrsDetectorParameters The sampleRate-dependent parameters
	 */
	public QRSDetector2(QRSDetectorParameters qrsDetectorParameters) 
		{
		qrsDetParas = qrsDetectorParameters;
		preBlankParas = new PreBlankParameters(qrsDetectorParameters, qrsDetectorParameters.MS195) ;
		sbcount = qrsDetectorParameters.MS1500 ;
		DDBuffer = new int[preBlankParas.DER_DELAY] ;
		for(int i = 0; i < 8; ++i)
			{
			rrbuf[i] = qrsDetectorParameters.MS1000 ;/* Initialize R-to-R interval buffer. */
			}
		}
	
	/**
	 * Injects the object.
	 * 
	 * @param qrsFilterer The qrsFilterer
	 */
	public void setObjects(QRSFilterer qrsFilterer)
		{
		this.qrsFilterer = qrsFilterer;
		}
	
	/**
	 * QRSDet implements a QRS detection algorithm.
	 * Consecutive ECG samples are passed to QRSDet. 
	 * When a QRS complex is detected QRSDet returns the detection delay.
	 * 
	 * @param datum sample of an ECG signal
	 * @return the detection delay if a QRS complex is detected
	 */
	public int QRSDet( int datum )
		{
		int fdatum, QrsDelay = 0 ;
		int i, newPeak, aPeak ;

		fdatum = qrsFilterer.QRSFilter(datum) ;	/* Filter data. */

		/* Wait until normal detector is ready before calling early detections. */

		aPeak = Peak(fdatum) ;
		if(aPeak < MIN_PEAK_AMP)
			aPeak = 0 ;
	
		// Hold any peak that is detected for 200 ms
		// in case a bigger one comes along.  There
		// can only be one QRS complex in any 200 ms window.

		newPeak = 0 ;
		if(aPeak != 0 && preBlankCnt == 0)			// If there has been no peak for 200 ms
			{										// save this one and start counting.
			tempPeak = aPeak ;
			preBlankCnt = preBlankParas.PRE_BLANK ;			// MS200
			}

		else if(aPeak == 0 && preBlankCnt != 0)	// If we have held onto a peak for
			{										// 200 ms pass it on for evaluation.
			if(--preBlankCnt == 0)
				newPeak = tempPeak ;
			}

		else if(aPeak != 0)							// If we were holding a peak, but
			{										// this ones bigger, save it and
			if(aPeak > tempPeak)				// start counting to 200 ms again.
				{
				tempPeak = aPeak ;
				preBlankCnt = preBlankParas.PRE_BLANK ; // MS200
				}
			else if(--preBlankCnt == 0)
				newPeak = tempPeak ;
			}

		/* Save derivative of raw signal for T-wave and baseline
		   shift discrimination. */
		
		DDBuffer[DDPtr] = qrsFilterer.deriv1( datum ) ;
		if(++DDPtr == preBlankParas.DER_DELAY)
			DDPtr = 0 ;

		/* Initialize the qrs peak buffer with the first eight 	*/
		/* local maximum peaks detected.						*/

		if( qpkcnt < 8 )
			{
			++count ;
			if(newPeak > 0) count = qrsDetParas.WINDOW_WIDTH ;
			if(++initBlank == qrsDetParas.MS1000)
				{
				initBlank = 0 ;
				qrsbuf[qpkcnt] = initMax ;
				initMax = 0 ;
				++qpkcnt ;
				if(qpkcnt == 8)
					{
					qmean = mean( qrsbuf, 8 ) ;
					nmean = 0 ;
					rrmean = qrsDetParas.MS1000 ;
					sbcount = qrsDetParas.MS1500+qrsDetParas.MS150 ;
					det_thresh = thresh(qmean,nmean) ;
					}
				}
			if( newPeak > initMax )
				initMax = newPeak ;
			}

		else	/* Else test for a qrs. */
			{
			++count ;
			if(newPeak > 0)
				{
				
				
				/* Check for maximum derivative and matching minima and maxima
				   for T-wave and baseline shift rejection.  Only consider this
				   peak if it doesn't seem to be a base line shift. */
				int[] maxderArray = new int[]{maxder};
				boolean result = BLSCheck(DDBuffer, DDPtr, maxderArray);
				maxder = maxderArray[0];
				if(!result)
					{

					// Classify the beat as a QRS complex
					// if the peak is larger than the detection threshold.

					if(newPeak > det_thresh)
						{
						System.arraycopy(qrsbuf, 0, qrsbuf, 1, MEMMOVELEN) ;
						qrsbuf[0] = newPeak ;
						qmean = mean(qrsbuf,8) ;
						det_thresh = thresh(qmean,nmean) ;
						System.arraycopy(rrbuf, 0, rrbuf, 1, MEMMOVELEN) ;
						rrbuf[0] = count - qrsDetParas.WINDOW_WIDTH ;
						rrmean = mean(rrbuf,8) ;
						sbcount = rrmean + (rrmean >> 1) + qrsDetParas.WINDOW_WIDTH ;
						count = qrsDetParas.WINDOW_WIDTH ;

						sbpeak = 0 ;
						maxder = 0 ;
						QrsDelay =  qrsDetParas.WINDOW_WIDTH + preBlankParas.FILTER_DELAY ;
						initBlank = initMax = rsetCount = 0 ;
						}

					// If a peak isn't a QRS update noise buffer and estimate.
					// Store the peak for possible search back.

					else
						{
						System.arraycopy(noise, 0, noise, 1, MEMMOVELEN) ;
						noise[0] = newPeak ;
						nmean = mean(noise,8) ;
						det_thresh = thresh(qmean,nmean) ;

						// Don't include early peaks (which might be T-waves)
						// in the search back process.  A T-wave can mask
						// a small following QRS.

						if((newPeak > sbpeak) && ((count-qrsDetParas.WINDOW_WIDTH) >= qrsDetParas.MS360))
							{
							sbpeak = newPeak ;
							sbloc = count  - qrsDetParas.WINDOW_WIDTH ;
							}
						}
					}
				}
			
			/* Test for search back condition.  If a QRS is found in  */
			/* search back update the QRS buffer and det_thresh.      */

			if((count > sbcount) && (sbpeak > (det_thresh >> 1)))
				{
				System.arraycopy(qrsbuf, 0, qrsbuf, 1, MEMMOVELEN);
				qrsbuf[0] = sbpeak ;
				qmean = mean(qrsbuf,8) ;
				det_thresh = thresh(qmean,nmean) ;
				System.arraycopy(rrbuf, 0, rrbuf, 1, MEMMOVELEN);
				rrbuf[0] = sbloc ;
				rrmean = mean(rrbuf,8) ;
				sbcount = rrmean + (rrmean >> 1) + qrsDetParas.WINDOW_WIDTH ;
				QrsDelay = count = count - sbloc ;
				QrsDelay += preBlankParas.FILTER_DELAY ;
				sbpeak = 0 ;
				maxder = 0 ;
				initBlank = initMax = rsetCount = 0 ;
				}
			}

		// In the background estimate threshold to replace adaptive threshold
		// if eight seconds elapses without a QRS detection.

		if( qpkcnt == 8 )
			{
			if(++initBlank == qrsDetParas.MS1000)
				{
				initBlank = 0 ;
				rsetBuff[rsetCount] = initMax ;
				initMax = 0 ;
				++rsetCount ;

				// Reset threshold if it has been 8 seconds without
				// a detection.

				if(rsetCount == 8)
					{
					for(i = 0; i < 8; ++i)
						{
						qrsbuf[i] = rsetBuff[i] ;
						noise[i] = 0 ;
						}
					qmean = mean( rsetBuff, 8 ) ;
					nmean = 0 ;
					rrmean = qrsDetParas.MS1000 ;
					sbcount = qrsDetParas.MS1500+qrsDetParas.MS150 ;
					det_thresh = thresh(qmean,nmean) ;
					initBlank = initMax = rsetCount = 0 ;
					}
				}
			if( newPeak > initMax )
				initMax = newPeak ;
			}

		return(QrsDelay) ;
		}

	/**
	 * peak() takes a datum as input and returns a peak height
	 * when the signal returns to half its peak height, or
	 * 
	 * @param datum
	 * @return The peak height
	 */
	private int Peak( int datum )
		{
		int pk = 0 ;

		if(Peak_timeSinceMax > 0)
			++Peak_timeSinceMax ;

		if((datum > Peak_lastDatum) && (datum > Peak_max))
			{
			Peak_max = datum ;
			if(Peak_max > 2)
				Peak_timeSinceMax = 1 ;
			}

		else if(datum < (Peak_max >> 1))
			{
			pk = Peak_max ;
			Peak_max = 0 ;
			Peak_timeSinceMax = 0 ;
			}

		else if(Peak_timeSinceMax > qrsDetParas.MS95)
			{
			pk = Peak_max ;
			Peak_max = 0 ;
			Peak_timeSinceMax = 0 ;
			}
		Peak_lastDatum = datum ;
		return(pk) ;
		}

	/**
	 * mean returns the mean of an array of integers.  It uses a slow
	 * sort algorithm, but these arrays are small, so it hardly matters.
	 * 
	 * @param array
	 * @param datnum
	 * @return The mean
	 */
	private int mean(int[] array, int datnum)
		{
		long sum ;
		int i ;
	
		for(i = 0, sum = 0; i < datnum; ++i)
			sum += array[i] ;
		sum /= datnum ;
		return (int) (sum) ;
		}

	/**
	 * thresh() calculates the detection threshold from the qrs median and noise
	 * median estimates.
	 * 
	 * @param qmedian
	 * @param nmedian
	 * @return The detection threshold
	 */
	private int thresh(int qmean, int nmean)
		{
		int thrsh, dmed ;
		double temp ;
		dmed = qmean - nmean ;
		temp = dmed ;
		temp *= TH ;
		dmed = (int) temp ;
		thrsh = nmean + dmed ; /* dmed * THRESHOLD */
		return(thrsh) ;
		}

	/**
	 * BLSCheck() reviews data to see if a baseline shift has occurred.
	 * This is done by looking for both positive and negative slopes of
	 * roughly the same magnitude in a 220 ms window.
	 * 
	 * @param dBuf
	 * @param dbPtr
	 * @param maxder
	 * @return If a baseline shift occured
	 */
	private boolean BLSCheck(int[] dBuf, int dbPtr, int[] maxder)
		{
		int max, min, maxt = 0, mint = 0, t, x ;
		max = min = 0 ;
	
		for(t = 0; t < qrsDetParas.MS220; ++t)
			{
			x = dBuf[dbPtr] ;
			if(x > max)
				{
				maxt = t ;
				max = x ;
				}
			else if(x < min)
				{
				mint = t ;
				min = x;
				}
			if(++dbPtr == preBlankParas.DER_DELAY)
				dbPtr = 0 ;
			}
	
		maxder[0] = max ;
		min = -min ;
		
		/* Possible beat if a maximum and minimum pair are found
			where the interval between them is less than 150 ms. */
		   
		if((max > (min>>3)) && (min > (max>>3)) &&
			(Math.abs(maxt - mint) < qrsDetParas.MS150))
			return (false) ;
			
		else
			return (true) ;
		}
	}
