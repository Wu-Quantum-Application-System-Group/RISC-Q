FROM --platform=linux/amd64 ubuntu:24.04

ENV LANG=en_US.UTF-8
ENV LANGUAGE=en_US:en
ENV LC_ALL=en_US.UTF-8

RUN apt-get update
RUN apt-get install -y git git-lfs
RUN apt-get install -y wget tar curl locales
RUN apt-get install -y openjdk-17-jdk build-essential python3 python3-pip python3-dev

RUN apt clean && \
    apt autoclean && \
    rm -rf /var/cache/apt/*

RUN pip install --break-system-packages numpy matplotlib scipy pyelftools

WORKDIR /opt
RUN wget https://github.com/YosysHQ/oss-cad-suite-build/releases/download/2025-07-05/oss-cad-suite-linux-x64-20250705.tgz
RUN tar xvf oss-cad-suite-*.tgz
RUN rm oss-cad-suite-*.tgz
ENV PATH="/opt/oss-cad-suite/bin:/opt/bin:${PATH}"

RUN wget https://github.com/riscv-collab/riscv-gnu-toolchain/releases/download/2025.01.20/riscv32-elf-ubuntu-24.04-llvm-nightly-2025.01.20-nightly.tar.xz
RUN tar xvf riscv*.tar.xz
# RUN mv riscv /opt/riscv
RUN rm riscv*.tar.xz
ENV PATH="/opt/riscv/bin:${PATH}"

RUN curl -fL "https://github.com/coursier/launchers/raw/master/cs-x86_64-pc-linux.gz" | gzip -d > cs && \
    chmod +x cs && \
    mkdir -p /opt/bin && \
    mv ./cs /opt/bin/cs

ARG MILL_VERSION="0.11.11"
RUN curl -L -o /opt/bin/mill https://github.com/lihaoyi/mill/releases/download/$MILL_VERSION/$MILL_VERSION && \
    chmod +x /opt/bin/mill && \
    touch build.sc && \
    mill -i resolve _ && \
    rm build.sc

