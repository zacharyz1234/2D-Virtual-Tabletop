import socket as s
import threading

PORT = 8080

def host_room(): #Logic for if players are hosting a room

    players = {}
    room_lock = threading.Lock()

    def broadcast(message, sender=None):
        with room_lock:
            for player in players:
                if player is sender:
                    continue

                try:
                    player.sendall(message)
                except OSError:
                    continue

    def send_host_messages():
        while True:
            try:
                message = input()
            except EOFError:
                return

            broadcast(f"Player 1 (Host): {message}\n".encode("utf-8"))

    def handle_player(connection, player_number):
        try:
            with connection.makefile("rb") as messages:
                for message in messages:
                    body = message.decode("utf-8", errors="replace").rstrip("\r\n")
                    chat_line = f"Player {player_number}: {body}"
                    print(chat_line)
                    broadcast((chat_line + "\n").encode("utf-8"), sender=connection)
        except OSError:
            pass
        finally:
            with room_lock:
                players.pop(connection, None)
            connection.close()

    with s.socket(s.AF_INET, s.SOCK_STREAM) as server_socket:
        server_socket.bind(("0.0.0.0", PORT))
        server_socket.listen()
        print(f"Room is listening on port {PORT}")

        try:
            with s.socket(s.AF_INET, s.SOCK_DGRAM) as route_socket:
                route_socket.connect(("8.8.8.8", 80))
                host_ip = route_socket.getsockname()[0]

            print(f"Host IP: {host_ip}")
            print("Players on the same Wi-Fi can enter this IP when joining.")
        except OSError:
            print("Could not detect the host IP automatically.")

        print("Type a message and press Enter to send it to the room.")
        threading.Thread(target=send_host_messages, daemon=True).start()

        while True:
            connection, address = server_socket.accept()

            with room_lock:
                room_full = len(players) >= 3
                if not room_full:
                    player_number = next(
                        number for number in range(2, 5)
                        if number not in players.values()
                    )
                    players[connection] = player_number

            if room_full:
                with connection:
                    try:
                        connection.sendall(b"Room is full.\n")
                    except OSError:
                        pass
                continue

            print(f"Player {player_number} connected from {address[0]}")
            threading.Thread(
                target=handle_player,
                args=(connection, player_number),
                daemon=True,
            ).start()


def join_room(): #Logic for if players are joining a room
    # Need to set the server ip and port so the client knows where to send info
    server_ip = input("Host IP: ").strip() or "127.0.0.1"

    def receive_messages(connection):
        try:
            with connection.makefile("rb") as messages:
                for message in messages:
                    print(message.decode("utf-8", errors="replace").rstrip())
        except OSError:
            pass
        print("Disconnected from the room.")

    try:
        with s.create_connection((server_ip, PORT)) as client_socket:
            threading.Thread(
                target=receive_messages,
                args=(client_socket,),
                daemon=True,
            ).start()

            print("Type a message or type /quit to leave.")
            while True:
                message = input()
                if message == "/quit":
                    break
                client_socket.sendall((message + "\n").encode("utf-8"))
    except(EOFError, KeyboardInterrupt):
        pass
    except OSError as error:
        print(f"Room connection error: {error}")



choice = input("Host or join? ").strip().lower()

if choice == "host":
    host_room()
elif choice == "join":
    join_room()
else:
    print("Invalid option")