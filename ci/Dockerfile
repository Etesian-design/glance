# SPDX-License-Identifier: Apache-2.0
# Glance's release builder (ci/release.sh): JDK 21, the Android SDK Glance builds with, apksigner and gpg.
FROM eclipse-temurin:21-jdk-noble
RUN apt-get update && apt-get install -y --no-install-recommends unzip gnupg && rm -rf /var/lib/apt/lists/*
ENV ANDROID_HOME=/sdk
# Android's command-line tools, checked against the SHA-1 Google publishes in repository2-3.xml.
RUN curl -fsSL -o /tmp/clt.zip https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip \
 && echo "e025545c62a8e64c7559119566a569fb1dec5f60  /tmp/clt.zip" | sha1sum -c - \
 && mkdir -p /sdk/cmdline-tools && unzip -q /tmp/clt.zip -d /sdk/cmdline-tools && mv /sdk/cmdline-tools/cmdline-tools /sdk/cmdline-tools/latest \
 && rm /tmp/clt.zip \
 && yes | /sdk/cmdline-tools/latest/bin/sdkmanager --licenses >/dev/null \
 && /sdk/cmdline-tools/latest/bin/sdkmanager "platforms;android-37.0" "build-tools;36.0.0" "platform-tools" >/dev/null \
 && chown -R 1000:1000 /sdk   # the build runs as the DeskMini user (ci/release.sh -u 1000)
ENV PATH=/sdk/build-tools/36.0.0:$PATH
