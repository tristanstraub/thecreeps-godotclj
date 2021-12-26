#!/usr/bin/env bash
exec clj -M -e "(require 'godotclj.runner) (godotclj.runner/start \"-v\")"
