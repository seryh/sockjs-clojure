#!/bin/bash

TARGET=../../../../../target
SOCKJS=$TARGET/sockjs-protocol

if [ ! -d $TARGET ]
then
    mkdir $TARGET
fi

# TODO: check if python-virtualenv is installed

if [ ! -d $SOCKJS ]
then
    git clone https://github.com/sockjs/sockjs-protocol.git $SOCKJS
    ( cd $SOCKJS && make )
fi

( cd $SOCKJS && ./venv/bin/python sockjs-protocol-0.3.3.py )
