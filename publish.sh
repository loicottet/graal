#!/bin/sh

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
