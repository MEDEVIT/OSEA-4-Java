OSEA-4-Java
===========

This is a Java Port of the software OSEA which was developed by Patrick S. Hamilton from EP Limited. 
The original source code was written in C. This project contains the code rewritten in Java. 
The software is capable of QRS-Detection and QRS-Classification.

License
-------

This project is licensed under the terms of the MIT license. See license.txt.

Version
-------

1.0.0: OSEA source code rewritten in Java - based on OSEA Release 1.3 (9/19/2002)

OSEA
----

OSEA is an **O**pen **S**ource **E**CG **A**nalysis software.
The software was originally developed by Patrick S. Hamilton from [EP Limited](http://www.eplimited.com) 
and can be downloaded at their [website] (http://www.eplimited.com/software.htm). 
For more information about OSEA see [the documentation of OSEA] (http://www.eplimited.com/osea13.pdf).

OSEA-4-Java
-----------

OSEA-4-Java is the result of the source code from OSEA rewritten in Java.

Usage
=====

This project contains functionality for QRS-Detection and QRS-Classification. The detectors/classifiers 
are having internal state about the last ecgSamples. To reset this state create a new instance with the factory.
Therefore for every detection/classification of ecgSamples an own object has to be used.

QRS-Detection
-------------

    int sampleRate = ... ;
    int[] ecgSamples = ... ;

    QRSDetector2 qrsDetector = OSEAFactory.createQRSDetector2(sampleRate);
    for (int i = 0; i < ecgSamples.length; i++) {
      int result = qrsDetector.QRSDet(ecgSamples[i]);
      if (result != 0) {
        System.out.println("A QRS-Complex was detected at sample: " + (i-result));
      }
    }

QRS-Classification
------------------

    int sampleRate = ... ;
    int[] ecgSamples = ... ;

    BeatDetectionAndClassification bdac = OSEAFactory.createBDAC(sampleRate, sampleRate/2);
    for (int i = 0; i < ecgSamples.length; i++) {
      BeatDetectAndClassifyResult result = bdac.BeatDetectAndClassify(ecgSamples[i]);
      if (result.samplesSinceRWaveIfSuccess != 0) {
        int qrsPosition =  i - result.samplesSinceRWaveIfSuccess;
        if (result.beatType == ECGCODES.UNKNOWN) {
          System.out.println("A unknown beat type was detected at sample: " + qrsPosition);
        } else if (result.beatType == ECGCODES.NORMAL) {
          System.out.println("A normal beat type was detected at sample: " + qrsPosition);
        } else if (result.beatType == ECGCODES.PVC) {
          System.out.println("A premature ventricular contraction was detected at sample: " + qrsPosition);
        }
      }
    }

