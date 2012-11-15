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

/**
 * This file includes QRSFilt() and associated filtering files used for QRS detection.
 */
public class QRSFilterer {

	private QRSDetectorParameters qrsDetParas ;
	
	private long  lpfilt_y1 = 0 ;
	private long  lpfilt_y2 = 0 ;
	private int[] lpfilt_data ;
	private int   lpfilt_ptr = 0 ;
	
	private long  hpfilt_y=0 ;
	private int[] hpfilt_data ;
	private int   hpfilt_ptr = 0 ;
	
	private int[] deriv1_derBuff ;
	private int   deriv1_derI = 0;
	
	private int[] deriv2_derBuff ;
	private int   deriv2_derI = 0;
	
	private long  mvwint_sum = 0 ;
	private int[] mvwint_data ;
	private int   mvwint_ptr = 0 ;
	
	/**
	 * Create a new filterer with the given parameters.
	 * @param qrsDetectorParameters The sampleRate-dependent parameters
	 */
	public QRSFilterer(QRSDetectorParameters qrsDetectorParameters)
		{
		qrsDetParas = qrsDetectorParameters ;
		lpfilt_data    = new int[qrsDetectorParameters.LPBUFFER_LGTH] ;
		hpfilt_data    = new int[qrsDetectorParameters.HPBUFFER_LGTH] ;
		deriv1_derBuff = new int[qrsDetectorParameters.DERIV_LENGTH] ;
		deriv2_derBuff = new int[qrsDetectorParameters.DERIV_LENGTH] ;
		mvwint_data    = new int[qrsDetectorParameters.WINDOW_WIDTH];
		}
	
	/**
	 * QRSFilter() takes samples of an ECG signal as input and returns a sample of
	 * a signal that is an estimate of the local energy in the QRS bandwidth.  In
	 * other words, the signal has a lump in it whenever a QRS complex, or QRS
	 * complex like artifact occurs.  The filters were originally designed for data
	 * sampled at 200 samples per second, but they work nearly as well at sample
	 * frequencies from 150 to 250 samples per second.
	 * 
	 * @param datum sample of an ECG signal
	 * @return a sample of a signal that is an estimate of the local energy in the QRS bandwidth
	 */
	public int QRSFilter(int datum)
		{
		int fdatum ;
		fdatum = lpfilt( datum ) ;  // Low pass filter data.
		fdatum = hpfilt( fdatum ) ; // High pass filter data.
		fdatum = deriv2( fdatum ) ; // Take the derivative.
		fdatum = Math.abs(fdatum) ;	// Take the absolute value.
		fdatum = mvwint( fdatum ) ; // Average over an 80 ms window .
		return(fdatum) ;
		}
	
	/**
	 * lpfilt() implements the digital filter represented by the difference equation:
	 * 
	 *   y[n] = 2*y[n-1] - y[n-2] + x[n] - 2*x[t-24 ms] + x[t-48 ms]
	 * 
	 * Note that the filter delay is (LPBUFFER_LGTH/2)-1
	 * 
	 * @param datum sample of an ECG signal
	 * @return the result of the filtering
	 */
	private int lpfilt( int datum )
		{
		long y0 ;
		int output ;
		int halfPtr ;

		halfPtr = lpfilt_ptr-(qrsDetParas.LPBUFFER_LGTH/2) ; // Use halfPtr to index
		if(halfPtr < 0) // to x[n-6].
			halfPtr += qrsDetParas.LPBUFFER_LGTH ;
		y0 = (lpfilt_y1 << 1) - lpfilt_y2 + datum - (lpfilt_data[halfPtr] << 1) + lpfilt_data[lpfilt_ptr] ;
		lpfilt_y2 = lpfilt_y1;
		lpfilt_y1 = y0;
		output = (int) y0 / ((qrsDetParas.LPBUFFER_LGTH*qrsDetParas.LPBUFFER_LGTH)/4);
		lpfilt_data[lpfilt_ptr] = datum ; // Stick most recent sample into
		if(++lpfilt_ptr == qrsDetParas.LPBUFFER_LGTH) // the circular buffer and update
			lpfilt_ptr = 0 ; // the buffer pointer.
		return(output) ;
		}
	
	/**
	 * hpfilt() implements the high pass filter represented by the following difference equation:
	 * 
	 *   y[n] = y[n-1] + x[n] - x[n-128 ms]
	 *   z[n] = x[n-64 ms] - y[n]
	 * 
	 * Filter delay is (HPBUFFER_LGTH-1)/2
	 * 
	 * @param datum sample of an ECG signal
	 * @return the result of the filtering
	 */
	private int hpfilt( int datum )
		{
		int z ;
		int halfPtr ;

		hpfilt_y += datum - hpfilt_data[hpfilt_ptr];
		halfPtr = hpfilt_ptr-(qrsDetParas.HPBUFFER_LGTH/2) ;
		if(halfPtr < 0)
			halfPtr += qrsDetParas.HPBUFFER_LGTH ;
		z = (int) (hpfilt_data[halfPtr] - (hpfilt_y / qrsDetParas.HPBUFFER_LGTH));
		hpfilt_data[hpfilt_ptr] = datum ;
		if(++hpfilt_ptr == qrsDetParas.HPBUFFER_LGTH)
			hpfilt_ptr = 0 ;
		return( z );
		}

	/**
	 * deriv1 and deriv2 implement derivative approximations represented by the difference equation:
	 * 
	 *   y[n] = x[n] - x[n - 10ms]
	 *   
	 *  Filter delay is DERIV_LENGTH/2
	 * 
	 * @param datum sample of an ECG signal
	 * @return the result of the derivative approximation
	 */
	public int deriv1( int x )
		{
		int y ;
		y = x - deriv1_derBuff[deriv1_derI] ;
		deriv1_derBuff[deriv1_derI] = x ;
		if(++deriv1_derI == qrsDetParas.DERIV_LENGTH)
			deriv1_derI = 0 ;
		return(y) ;
		}
	
	/**
	 * deriv1 and deriv2 implement derivative approximations represented by the difference equation:
	 * 
	 *   y[n] = x[n] - x[n - 10ms]
	 *   
	 *  Filter delay is DERIV_LENGTH/2
	 * 
	 * @param datum sample of an ECG signal
	 * @return the result of the derivative approximation
	 */
	private int deriv2( int x )
		{
		int y ;
		y = x - deriv2_derBuff[deriv2_derI] ;
		deriv2_derBuff[deriv2_derI] = x ;
		if(++deriv2_derI == qrsDetParas.DERIV_LENGTH)
			deriv2_derI = 0 ;
		return(y) ;
		}
	
	/**
	 * mvwint() implements a moving window integrator.  Actually, mvwint() averages
	 * the signal values over the last WINDOW_WIDTH samples.
	 * 
	 * @param datum sample of an ECG signal
	 * @return the average
	 */
	private int mvwint( int datum )
		{
		int output;
		mvwint_sum += datum ;
		mvwint_sum -= mvwint_data[mvwint_ptr] ;
		mvwint_data[mvwint_ptr] = datum ;
		if(++mvwint_ptr == qrsDetParas.WINDOW_WIDTH)
			mvwint_ptr = 0 ;
		if((mvwint_sum / qrsDetParas.WINDOW_WIDTH) > 32000)
			output = 32000 ;
		else
			output = (int) (mvwint_sum / qrsDetParas.WINDOW_WIDTH) ;
		return(output) ;
		}
}
