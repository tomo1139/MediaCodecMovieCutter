#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>

void filtering(short* pInputData, fpos_t inputDataSize, short* pFilteredFileData);
void resampling(short* pFilteredFileData, int inputDataSize, short* pOutputData, int outputDataSize);
void createHanningWindow(double w[], int N);
double sinc(double x);
void createFirLpf(double fe, int J, double b[], double w[]);

jstring Java_develop_tomo1139_mediacodecmoviecutter_media_ResampledRawAudioExtractor_resample(
    JNIEnv* env,
    jobject javaThis,
    jstring _inputFilePath,
    jstring _outputFilePath
) {
    // load input file
    const char *inputFilePath = (*env)->GetStringUTFChars(env, _inputFilePath, 0);
    FILE* readFp = fopen(inputFilePath, "rb");
    if (readFp == NULL) {
        return (*env)->NewStringUTF(env, "failed fopen");
    }
    fpos_t inputDataSize = 0;
    fseek(readFp, 0L, SEEK_END);
    fgetpos(readFp, &inputDataSize);
    fseek(readFp, 0L, SEEK_SET);
    short* pInputData = (short*)calloc(1, inputDataSize);
    fread(pInputData, inputDataSize, 1, readFp);
    fclose(readFp);

    // filtering
    short* pFilteredFileData = (short*)calloc(1, inputDataSize);
    filtering(pInputData, inputDataSize, pFilteredFileData);
    free(pInputData);

    // resampling
    int outputDataSize = inputDataSize * 44.1 / 48;
    short* pOutputData = (short*)calloc(1, outputDataSize);
    resampling(pFilteredFileData, inputDataSize, pOutputData, outputDataSize);

    // write output file
    const char *outputFilePath = (*env)->GetStringUTFChars(env, _outputFilePath, 0);
    FILE* writeFp = fopen(outputFilePath, "wb");
    if (writeFp == NULL) {
        free(pFilteredFileData);
        return (*env)->NewStringUTF(env, "failed fopen");
    }
    fwrite(pOutputData, outputDataSize, 1, writeFp);

    free(pFilteredFileData);
    free(pOutputData);
    fclose(writeFp);

    return (*env)->NewStringUTF(env, "success");
}

void filtering(short* pInputData, fpos_t inputDataSize, short* pFilteredFileData) {
    double edgeFrequency = 21000.0 / 48000;
    double delta = 1000.0 / 48000;

    int delayMachineNum = (int)(3.1 / delta + 0.5) -1;
    if (delayMachineNum % 2 == 1) {
        delayMachineNum++;
    }

    double* hanningWindow = calloc((delayMachineNum + 1), sizeof(double));
    createHanningWindow(hanningWindow, (delayMachineNum+1));

    double* firLpf = calloc((delayMachineNum + 1), sizeof(double));
    createFirLpf(edgeFrequency, delayMachineNum, firLpf, hanningWindow);

    // filtering
    for (int n = 0; n < inputDataSize / sizeof(short); n++) {
        double filteredData = 0.0;
        for (int m = 0; m <= delayMachineNum; m++) {
            if (n - m >= 0) {
                filteredData += firLpf[m] * pInputData[n - m] / 32768.0;
            }
        }
        pFilteredFileData[n] = (short)(filteredData * 32768);
    }

    free(hanningWindow);
    free(firLpf);
}

void resampling(short* pFilteredFileData, int inputDataSize, short* pOutputData, int outputDataSize) {
    // 線形補間
    for (int i=0; i<outputDataSize/sizeof(short); i++) {
       float targetPos = i * 48 / 44.1;
       int iTargetPos = (int) targetPos;

       int startData = pFilteredFileData[iTargetPos];
       int endData = 0;
       if (iTargetPos + 1 < inputDataSize) {
           endData = pFilteredFileData[iTargetPos+1];
       }

       int targetData = (endData - startData) * (targetPos - iTargetPos) + startData;
       pOutputData[i] = targetData;
    }
}

void createHanningWindow(double w[], int N) {
  int n;

  if (N % 2 == 0) /* Nが偶数のとき */
  {
    for (n = 0; n < N; n++)
    {
      w[n] = 0.5 - 0.5 * cos(2.0 * M_PI * n / N);
    }
  }
  else /* Nが奇数のとき */
  {
    for (n = 0; n < N; n++)
    {
      w[n] = 0.5 - 0.5 * cos(2.0 * M_PI * (n + 0.5) / N);
    }
  }
}

void createFirLpf(double fe, int J, double b[], double w[]) {
    int m;
    int offset;

    offset = J / 2;
    for (m = -J / 2; m <= J / 2; m++) {
        b[offset + m] = 2.0 * fe * sinc(2.0 * M_PI * fe * m);
    }

    for (m = 0; m < J + 1; m++) {
        b[m] *= w[m];
    }
}

double sinc(double x) {
    double y;

    if (x == 0.0) {
      y = 1.0;
    } else {
      y = sin(x) / x;
    }
    return y;
}
