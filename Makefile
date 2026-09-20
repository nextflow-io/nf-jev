# Use the Gradle wrapper by default; override with e.g. `make GRADLE=gradle ...`
# or `export GRADLE=gradle` (useful in pixi/conda environments).
GRADLE ?= ./gradlew

# Build the plugin
assemble:
	$(GRADLE) assemble

clean:
	rm -rf .nextflow*
	rm -rf work
	rm -rf build
	$(GRADLE) clean

# Run plugin unit tests
test:
	$(GRADLE) test

# Install the plugin into local nextflow plugins dir
install:
	$(GRADLE) install

# Publish the plugin
release:
	$(GRADLE) releasePlugin
