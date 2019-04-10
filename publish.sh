#!/bin/sh

old_java_home=$JAVA_HOME

export JAVA_HOME=/Library/Java/JavaVirtualMachines/labsjdk1.8.0_202-jvmci-0.58/Contents/Home/
cd substratevm
mx build
mx maven-install
cd ../compiler
mx maven-install
cd ../sdk
mx maven-install
cd ../truffle
mx maven-install
cd ..
export JAVA_HOME=$old_java_home