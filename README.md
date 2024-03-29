# Repro4PDE
日本語README: [README_ja.md](README_ja.md)

https://github.com/kgtkr/Repro4PDE/assets/17868838/2fc6d6ab-2c92-4424-bb1e-2f88ae78496c


## Install
1. Requires Processing 4.x
2. Download and unzip: https://kgtkr.github.io/Repro4PDE/Repro4PDE.zip 
3. Open the Processing Preferences and check the "Sketchbook folder" (hereinafter referred to as `<sketchbook>`)
4. Copy the unzipped `Repro4PDE` folder to `<sketchbook>/tools`. Check that `<sketchbook>/tools/Repro4PDE/tool.properties` exists
5. Restart Processing
6. Make sure `Repro4PDE` is added to `tools` in the menu.

## Usage
1. Open sketch
2. Select Repro4PDE from the tools menu (Untitled sketches are not supported)
3. Press the play button on the opened window(Not a play button in Processing Editor)
4. Edit and save your sketch and the program will automatically restart and hot reload.

The random number seed value is fixed for program reproducibility. You can change to another seed value by pressing the reset button. We plan to implement similar functionality in the time API, etc.

## Comparison with current code
By pressing `Enable comparison with current code`, you can check the sketch, code, and behavior at the same time when you press the button. Events etc. are synchronized, making it easier to check operation.

## Limitation
* Currently it cannot coexist with tools that use JavaFX.
  * We are considering solutions such as running the user interface in a separate Java VM.
* Does not support frame rate changes (only 60fps)
* It is not supported to specify the renderer with the third argument of `size`
* Programs with high CPU load may not work
* Operation when combined with libraries has not been confirmed
* `draw` is required (`noLoop` can't be used either)
* Must call `background` every frame (please reset the screen)

We plan to respond gradually.
