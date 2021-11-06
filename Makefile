PROJECT_DIR=$(PWD)
GODOT_HEADERS=$(PROJECT_DIR)/godot-headers
BUILD=$(PROJECT_DIR)/build
BIN=$(PROJECT_DIR)/bin
CLASSES=$(PWD)/classes
CLASSPATH=$(shell clj -Spath | clj -M -e "(require 'godotclj.paths)")

CLJ=clj -Scp $(CLASSPATH)

ifeq ($(RUNTIME),graalvm)
JAVA_HOME=$(GRAALVM_HOME)
else
JAVA_HOME=$(shell clj -M -e "(println (System/getProperty \"java.home\"))")
endif

JAVA_PATH=$(JAVA_HOME)/bin:$(PATH)

export PROJECT_DIR GODOT_HEADERS BUILD BIN CLASSES CLASSPATH

all: assets/dodge_assets assets/icon.png $(BIN)/libgodotclj_gdnative.so godotclj/src/clojure/godotclj/api/gdscript.clj

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

godotclj/src/clojure/godotclj/api/gdscript.clj:
	$(MAKE) -C godotclj src/clojure/godotclj/api/gdscript.clj

aot: godotclj/src/clojure/godotclj/api/gdscript.clj
	mkdir -p $(CLASSES)
	PATH=$(JAVA_PATH) \
	$(CLJ) -J-Dtech.v3.datatype.graal-native=true \
		-J-Dclojure.compiler.direct-linking=true \
		-J-Dclojure.spec.skip-macros=true \
		-M -e "(set! *warn-on-reflection* true) (with-bindings {#'*compile-path* (System/getenv \"CLASSES\")} (compile 'thecreeps.main))"

$(BIN)/%.so: godotclj/src/clojure/godotclj/api/gdscript.clj
	$(MAKE) -C godotclj $@
