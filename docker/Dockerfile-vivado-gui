# gui on web

FROM lscr.io/linuxserver/webtop:ubuntu-mate

# vivado dependency

RUN apt update
RUN apt install -y --no-install-recommends \
    python3-pip python3-dev build-essential git gcc-multilib g++ \
    ocl-icd-opencl-dev libjpeg62-dev libc6-dev-i386 graphviz make \
    unzip xvfb locales libswt-gtk-4-jni libncurses5-dev libtinfo-dev  
    #unzip libtinfo5 xvfb libncursesw5 locales libswt-gtk-4-jni
RUN ln -s /lib/x86_64-linux-gnu/libtinfo.so.6 /lib/x86_64-linux-gnu/libtinfo.so.5

RUN sed -i '/en_US.UTF-8/s/^# //g' /etc/locale.gen && \
    locale-gen
ENV LANG=en_US.UTF-8
ENV LANGUAGE=en_US:en
ENV LC_ALL=en_US.UTF-8

# spinal

RUN echo "deb https://repo.scala-sbt.org/scalasbt/debian all main" | tee /etc/apt/sources.list.d/sbt.list
RUN echo "deb https://repo.scala-sbt.org/scalasbt/debian /" | tee /etc/apt/sources.list.d/sbt_old.list
RUN curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x2EE0EA64E40A89B84B2DF73499E82A75642AC823" | apt-key add
RUN apt update

RUN apt install -y \
        openjdk-21-jdk \
        sbt \
        gtkwave \
        git \
        make \
        autoconf \
        g++ \
        flex \
        bison \
        help2man \
        device-tree-compiler \
        libboost-all-dev \
        gosu

# WORKDIR /opt
# RUN curl -L https://github.com/YosysHQ/oss-cad-suite-build/releases/download/2024-02-22/oss-cad-suite-linux-x64-20240222.tgz >> oss-cad-suite.tgz
# #RUN curl -L https://github.com/YosysHQ/oss-cad-suite-build/releases/download/2023-12-01/oss-cad-suite-linux-x64-20231201.tgz >> oss-cad-suite-linux-x64-20231201.tgz
# RUN tar -xzvf oss-cad-suite.tgz
# RUN rm oss-cad-suite.tgz

WORKDIR /root
RUN git clone https://github.com/verilator/verilator.git -b v5.032
WORKDIR verilator
RUN autoconf
RUN ./configure
RUN make -j8
RUN make install
WORKDIR /root
RUN rm -rf verilator

ARG MILL_VERSION="0.11.11"
RUN \
  curl -L -o /usr/local/bin/mill https://github.com/lihaoyi/mill/releases/download/$MILL_VERSION/$MILL_VERSION && \
  chmod +x /usr/local/bin/mill && \
  touch build.sc && \
  mill -i resolve _ && \
  rm build.sc

ENV PIP_BREAK_SYSTEM_PACKAGES=1
RUN pip install cocotb cocotb-test click jupyterlab && \
  pip cache purge

RUN curl -fL "https://github.com/coursier/launchers/raw/master/cs-x86_64-pc-linux.gz" | gzip -d > cs && \
  chmod +x cs && \
  mkdir -p /opt/bin && \
  mv ./cs /opt/bin/cs

RUN git clone https://github.com/serge1/ELFIO.git && \
  cd ELFIO && \
  git checkout d251da09a07dff40af0b63b8f6c8ae71d2d1938d && \
  cp -R elfio /usr/include && \
  cd .. && rm -rf ELFIO

# risc-v toolchain

RUN    apt-get update        \
    && apt-get install --yes \
       autoconf              \
       automake              \
       autotools-dev         \
       curl                  \
       python3               \
       libmpc-dev            \
       libmpfr-dev           \
       libgmp-dev            \
       gawk                  \
       build-essential       \
       bison                 \
       flex                  \
       texinfo               \
       gperf                 \
       libtool               \
       patchutils            \
       bc                    \
       zlib1g-dev            \
       libexpat-dev          \
       git

# ENV RISCV=/opt/riscv

# ARG TOOLCHAIN_VERSION=2024.04.12
# RUN    git clone --recursive https://github.com/riscv/riscv-gnu-toolchain -b ${TOOLCHAIN_VERSION} \
#     && cd riscv-gnu-toolchain                                                                     \
#     && ./configure --prefix=$RISCV --enable-multilib                                              \
#     && make                                                                                       \
#     && cd ..                                                                                      \
#     && rm -rf riscv-gnu-toolchain

ARG RISCV_TOOLCHAIN_VERSION="riscv64-unknown-elf-gcc-8.3.0-2019.08.0-x86_64-linux-ubuntu14"
RUN curl -L -o riscv64-unknown-elf-gcc.tar.gz https://static.dev.sifive.com/dev-tools/$RISCV_TOOLCHAIN_VERSION.tar.gz
RUN tar -xzvf riscv64-unknown-elf-gcc.tar.gz
RUN mv $RISCV_TOOLCHAIN_VERSION /opt/riscv
RUN rm riscv64-unknown-elf-gcc.tar.gz

# ENV LD_PRELOAD="/lib/x86_64-linux-gnu/libudev.so.1 /lib/x86_64-linux-gnu/libselinux.so.1 /lib/x86_64-linux-gnu/libz.so.1 /lib/x86_64-linux-gnu/libgdk-x11-2.0.so.0"

# ENV PATH=$PATH:/opt/riscv/bin:/opt/oss-cad-suite/bin:/opt/bin:/opt/Xilinx/Vivado/2024.2/bin/

RUN apt install openssh-server vim tmux -y

RUN apt clean autoclean
