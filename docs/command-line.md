# Running MeshApp from the command line

**Language:** [Русский](command-line.ru.md) | English | [Deutsch](command-line.de.md)

MeshApp normally opens a desktop window. Add `--terminal` to use the terminal interface (TUI), or `--rpc-server` to run a server that another copy of MeshApp can connect to.

## Using an installed copy

Open a terminal and run the command for your installation:

| Installation | Start the TUI |
| --- | --- |
| Linux, `.deb` package | `/opt/meshapp/bin/MeshApp --terminal` |
| Linux, AppImage | `./MeshApp-v2.4.0-linux-x86_64.AppImage --terminal` |
| Linux, Flatpak | `flatpak run app.privatepractice.meshapp --terminal` |
| macOS | `/Applications/MeshApp.app/Contents/MacOS/MeshApp --terminal` |

For AppImage, use the name of the file you downloaded. If it is not executable yet, run `chmod +x filename.AppImage` first.

To start the RPC server, replace `--terminal` with `--rpc-server`. For example, with the `.deb` package:

```bash
/opt/meshapp/bin/MeshApp --rpc-server --print-rpc-key
```

`--print-rpc-key` prints the access key you will need to connect.

### Windows

Open PowerShell in the MeshApp installation folder. Run `.\MeshApp.exe` for the desktop app. To start the RPC server, run:

```powershell
.\MeshAppRpcServer.exe --print-rpc-key
```

This executable starts the server directly; you do not need to add `--rpc-server`.

For the TUI, use `runTerminal` or `MeshApp.bat` as described below. The desktop executable, `MeshApp.exe`, does not send its output to the console you launched it from.

## Running from source

Install the [build dependencies](../README.md#requirements), then open a terminal in the project folder.

```bash
# Desktop app
./gradlew run

# TUI
./gradlew --console=plain runTerminal

# RPC server
./gradlew --console=plain runRpcServer
```

In PowerShell, use `.\gradlew.bat` instead of `./gradlew`.

Pass MeshApp options through `--args`. For example, to start the TUI and connect to a device over TCP:

```bash
./gradlew --console=plain runTerminal --args="--host 192.168.1.10"
```

`runTerminal` adds `--terminal` for you, and `runRpcServer` adds `--rpc-server`. Gradle's `--console=plain` option keeps its progress display from interfering with the terminal interface.

### Running without Gradle

Run `./gradlew installDist` to build a copy of the app in `build/install/MeshApp`. You can then launch it directly:

```bash
./build/install/MeshApp/bin/MeshApp --terminal
./build/install/MeshApp/bin/MeshApp --rpc-server
```

On Windows, build with `.\gradlew.bat installDist`, then use `MeshApp.bat`:

```powershell
.\build\install\MeshApp\bin\MeshApp.bat --terminal
.\build\install\MeshApp\bin\MeshApp.bat --rpc-server
```

This build needs Java 25 installed on your computer.

## Using the TUI

The examples below use `MeshApp` as shorthand. Replace it with the command for your installation from the sections above.

Run with `--terminal` to see your saved connections. Select one with the arrow keys and press `c` to connect. You can also specify a device when starting the app:

```bash
# TCP
MeshApp --terminal --host 192.168.1.10

# USB
MeshApp --terminal --serial /dev/ttyUSB0 --baud 115200

# Bluetooth, with a MeshCore Companion device
MeshApp --terminal --ble AA:BB:CC:DD:EE:FF --protocol meshcore-companion
```

Choose one connection option: `--host`, `--serial`, or `--ble`. MeshApp creates a temporary profile for it and removes the profile when you exit normally. USB port names usually look like `COM3` on Windows or `/dev/cu.usbmodem…` on macOS. For BLE, use the device address or identifier shown by your operating system.

| Option | Purpose |
| --- | --- |
| `--host HOST` | Device address for a TCP connection. |
| `--port PORT` | Device TCP port. Default: `4403`. |
| `--serial PORT` | Serial port, including USB connections. |
| `--baud RATE` | Serial baud rate. Default: `115200`. |
| `--ble ADDRESS` | Device address for a BLE connection. |
| `--protocol TYPE` | `meshtastic` (default), `meshcore-kiss`, or `meshcore-companion`. |
| `--name NAME` | Temporary profile name. Defaults to `Terminal TCP`, `Terminal Serial`, or `Terminal BLE`. |
| `--help`, `-h` | Show help and exit. |

Press `Tab` to switch panels and `PgUp` or `PgDn` to scroll chat history. When the message input is not focused, press `?` for help, `d` to disconnect, or `q` to quit. You can also press `/` to enter a command, such as `/connect 1`, `/channel 0`, `/dm !12345678`, or `/quit`.

## Running an RPC server

First, open the desktop app, create a connection to your radio, and enable auto-connect for it. Check that the connection works, then quit the app completely.

Start the server under the same user account:

```bash
MeshApp --rpc-server --print-rpc-key
```

The server loads your saved settings and connects profiles that have auto-connect enabled. If none do, the server runs without connecting to a radio. Server mode does not accept `--host`, `--serial`, or `--ble`.

If no access key has been set, MeshApp creates one, saves it, and prints it in the terminal. On later runs, use `--print-rpc-key` to see it again. Copy the whole key, including the `mra1_` prefix; a password of your own will not work.

### Connecting from another computer

By default, the server is available only on the same computer, at `127.0.0.1:44030`. To accept connections over the network, start it with:

```bash
MeshApp --rpc-server --rpc-bind 0.0.0.0 --rpc-port 44030 --print-rpc-key
```

In MeshApp on the other computer, add a **Remote RPC** connection and choose **Direct**. Enter the server computer's IP address, port `44030`, and the access key. Use the actual IP address, such as `192.168.1.20`, on the client; `0.0.0.0` is only for the server's startup command. If the connection fails, check that the firewall allows the port.

Press `Ctrl+C` in the server's terminal to stop it.

### Server options

| Option | Purpose |
| --- | --- |
| `--rpc-bind ADDRESS`, `--bind ADDRESS` | Address to listen on. Uses the saved address, or `127.0.0.1` if none is saved. |
| `--rpc-port PORT`, `--port PORT` | Port from `1` to `65535`. Uses the saved port, or `44030` if none is saved. |
| `--rpc-key KEY`, `--key KEY` | Use this access key instead of the saved key. |
| `--rpc-router [SERVER]`, `--rpc-router-server [SERVER]` | Connect to an External RPC Router. Omit the address to use the saved one. |
| `--no-rpc-router` | Disable the Router connection for this run. |
| `--print-rpc-key` | Print the access key in the terminal. Newly generated keys are printed automatically. |
| `--no-autoconnect` | Skip automatic device connections at startup. |
| `--help`, `-h` | Show help and exit. |

You can also set the address, port, and key through the environment variables `MESHAPP_RPC_BIND`, `MESHAPP_RPC_PORT`, and `MESHAPP_RPC_KEY`:

```bash
MESHAPP_RPC_BIND=127.0.0.1 MESHAPP_RPC_PORT=44031 MeshApp --rpc-server
```

MeshApp checks command-line options first, then environment variables, then saved settings. If no value is set, it uses the default or generates a key.

Command-line options and environment variables do not change your saved settings. A key generated by MeshApp is saved for future runs. `--rpc-server` starts the server even if it is disabled in the app's settings.

### Connecting through a Router

If a direct connection is not possible, you can use an External RPC Router:

```bash
MeshApp --rpc-server --rpc-router --print-rpc-key
```

MeshApp connects to your saved Router address. Unless you have changed it, this is `wss://cloud.meshapp.privatepractice.app`. To use your own Router:

```bash
MeshApp --rpc-server --rpc-router wss://router.example.org/rpc --print-rpc-key
```

On the client, choose **Router** and enter the same address and key. The Router must already be running; this command only connects to it. Direct TCP connections remain available too.

If you pass neither `--rpc-router` nor `--no-rpc-router`, MeshApp uses the Router setting saved in the app. See the [RPC documentation](rpc-protocol.md) for methods and message formats.

## Help and troubleshooting

TUI and server help are separate:

```bash
MeshApp --terminal --help
MeshApp --rpc-server --help
```

With Gradle, use `./gradlew runTerminal --args="--help"` or `./gradlew runRpcServer --args="--help"`. `MeshApp --help` on its own opens the desktop app.

Separate options from their values with a space: `--rpc-port 44030`. The `=` form is not supported. Quote names containing spaces: `--name "Home radio"`. With Gradle, use `--args="--host 192.168.1.10 --name 'Home radio'"`.

| Problem | What to check |
| --- | --- |
| `Unknown terminal option` or `Unknown RPC server option` | Check the spelling and whether the option is supported in that mode. |
| `Address already in use` | Another program is using the port. Stop it or choose a different `--rpc-port`. |
| The database cannot be opened | Quit any other copies of MeshApp running under the same user account. |
| USB port access is denied | See the [Linux USB instructions](../README.md#linux-usb-serial-access). |

All three modes share the database in `~/.meshapp`. Only one process can open it at a time. To run a server and client on the same computer, use separate user accounts.

`--no-single-instance` (also called `--allow-multiple-instances`) lets you open another desktop window. The Java setting `-Dmeshapp.singleInstance.disabled=true` does the same thing. Neither option creates a separate database.

TUI and RPC exit codes: `0` for normal exit or help, `1` for a startup failure, and `2` for invalid options.
