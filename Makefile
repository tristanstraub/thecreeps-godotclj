PROJECT_DIR=$(PWD)
GODOT_HEADERS=$(PROJECT_DIR)/godot-headers
BUILD=$(PROJECT_DIR)/build
BIN=$(PROJECT_DIR)/bin
CLASSES=$(PWD)/classes
CLASSPATH=$(shell clj -Spath | clj -M -e "(require 'godotclj.paths)")
export PROJECT_DIR GODOT_HEADERS BUILD BIN CLASSES CLASSPATH

all: godot-headers godotclj assets/dodge_assets assets/icon.png $(BIN)/libgodotclj_gdnative.so

clean:
	rm -fr .cpcache
	$(MAKE) -C godotclj clean

assets:
	mkdir -p assets

assets/dodge_assets.zip: assets
	curl https://docs.godotengine.org/en/stable/_downloads/e79a087a28c8eb4d140359198a122c0f/dodge_assets.zip -o assets/dodge_assets.zip

assets/dodge_assets: assets/dodge_assets.zip
	unzip -u assets/dodge_assets.zip -d assets

assets/icon.png: assets
	curl https://godotengine.org/themes/godotengine/assets/press/icon_color.png -o assets/icon.png

submodules:
	git submodule init
	git submodule update

godot-headers godotclj: submodules

$(BIN)/%.so: godot-headers godotclj
	$(MAKE) -C godotclj $@
