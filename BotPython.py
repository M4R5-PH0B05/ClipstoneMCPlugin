import os
import uuid
import asyncio
import aiomysql
import discord
from discord import app_commands
from dotenv import load_dotenv
import struct
import socket
import io

# ENV VARIABLES
load_dotenv()


# MAIN CLASS
class MCRegistrationClient(discord.Client):
    def __init__(self, *, intents: discord.Intents):
        super().__init__(intents=intents)
        self.pool = None
        self.tree = app_commands.CommandTree(self)

    # CONNECT TO DB
    async def setup_hook(self):
        self.pool = await aiomysql.create_pool(
            host=os.getenv('DB_HOST'),
            port=int(os.getenv('DB_PORT')),
            db=os.getenv('DB_NAME'),
            user=os.getenv('DB_USER'),
            password=os.getenv('DB_PASSWORD'),
            autocommit=True
        )

        await self.tree.sync()
        print("Synced Slash Commands.")


# MAIN BOT
class RegistrationBot:
    MAX_USERS = 20  # Maximum number of users allowed to register

    def __init__(self):
        # INTENTS
        print("")
        intents = discord.Intents.default()
        intents.message_content = True

        self.client = MCRegistrationClient(intents=intents)
        self.minecraft_server_host = os.getenv('MINECRAFT_SERVER_HOST', 'localhost')
        self.minecraft_server_port = int(os.getenv('MINECRAFT_SERVER_PORT', '25565'))
        self.setup_commands()

    def send_plugin_message(self, minecraft_uuid, discord_id):
        """
        Send a plugin message to the Minecraft server to link a Discord account via Bungeecord/Spigot plugin messaging

        Args:
            minecraft_uuid (str): Minecraft player UUID
            discord_id (int): Discord user ID

        Returns:
            bool: True if message sent successfully, False otherwise
        """
        try:
            # Create a socket connection to the Minecraft server
            with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
                # Connect to the Minecraft server
                sock.connect((self.minecraft_server_host, self.minecraft_server_port))

                # Create a byte stream for the plugin message
                output = io.BytesIO()

                # Write the Discord ID as a long (8 bytes)
                output.write(struct.pack('>Q', discord_id))

                # Write the Minecraft UUID as a UTF-8 string
                # Note: using .encode('utf-8') and sending as UTF string matches the Java readUTF()
                uuid_bytes = minecraft_uuid.encode('utf-8')
                output.write(uuid_bytes)

                # Get the complete message byte array
                message = output.getvalue()

                # Prepare the packet with the correct channel name used in the Java plugin
                packet = b'clipstone:registration' + message

                # Send the packet
                sock.sendall(packet)

                print(f"Successfully sent plugin message for UUID {minecraft_uuid}")
                return True

        except socket.error as e:
            print(f"Socket error when sending plugin message: {e}")
            return False
        except Exception as e:
            print(f"Error sending plugin message: {e}")
            return False

    def setup_commands(self):
        @self.client.tree.command(name='register', description='Register your Minecraft Account')
        @app_commands.describe(minecraft_uuid='Your Minecraft Account UUID')
        async def link_minecraft(interaction: discord.Interaction, minecraft_uuid: str):
            try:
                # VALIDATE UUID
                parsed_uuid = uuid.UUID(minecraft_uuid)

                if not self.client.pool:
                    await interaction.response.send_message("Error connecting to DB.", ephemeral=True)
                    return

                async with self.client.pool.acquire() as conn:
                    async with conn.cursor() as cursor:
                        try:
                            # First, check the total number of registered users
                            await cursor.execute('SELECT COUNT(*) as user_count FROM users')
                            result = await cursor.fetchone()
                            current_user_count = result[0]

                            # Check if we've reached the maximum number of users
                            if current_user_count >= self.MAX_USERS:
                                await interaction.response.send_message(
                                    f"Registration is full. Maximum of {self.MAX_USERS} users have already been registered.",
                                    ephemeral=True
                                )
                                return

                            # Check if this user is already registered
                            await cursor.execute('SELECT * FROM users WHERE discord_id = %s OR minecraft_uuid = %s',
                                                 (interaction.user.id, str(parsed_uuid)))
                            existing_user = await cursor.fetchone()

                            if existing_user:
                                await interaction.response.send_message(
                                    "Either your Discord account or this Minecraft account are already registered.",
                                    ephemeral=True
                                )
                                return

                            # Proceed with registration
                            await cursor.execute('''
                            INSERT INTO users (minecraft_uuid, discord_id, current_username)
                            VALUES (%s, %s, %s)
                            ON DUPLICATE KEY UPDATE
                            discord_id = %s,
                            current_username = %s
                            ''', (str(parsed_uuid), interaction.user.id, interaction.user.name,
                                  interaction.user.id, interaction.user.name))

                            # Send plugin message to Minecraft server
                            if self.send_plugin_message(str(parsed_uuid), interaction.user.id):
                                await interaction.response.send_message(
                                    f"Successfully initiated account link for UUID `{minecraft_uuid}`! "
                                    "Please run the /register command again in Minecraft in order to complete the process.",
                                    ephemeral=True
                                )
                            else:
                                await interaction.response.send_message(
                                    "Linked in database, but could not send message to Minecraft server.",
                                    ephemeral=True
                                )

                        except Exception as db_error:
                            await interaction.response.send_message(
                                f"Registration failed: {str(db_error)}",
                                ephemeral=True
                            )

            except ValueError:
                await interaction.response.send_message(
                    "Invalid UUID format. Please provide a valid minecraft UUID.",
                    ephemeral=True
                )

        @self.client.tree.command(name="checklink", description="Check your Minecraft account link")
        async def check_link(interaction: discord.Interaction):
            if not self.client.pool:
                await interaction.response.send_message("Database connection error. Please contact an admin.",
                                                        ephemeral=True)
                return

            async with self.client.pool.acquire() as conn:
                async with conn.cursor(aiomysql.DictCursor) as cursor:
                    await cursor.execute(
                        'SELECT minecraft_uuid, current_username FROM users WHERE discord_id = %s',
                        (interaction.user.id,)
                    )
                    result = await cursor.fetchone()

                    if result:
                        await interaction.response.send_message(
                            f"Your Discord is linked to the Minecraft account with the UUID: `{result['minecraft_uuid']}`",
                            ephemeral=True
                        )
                    else:
                        await interaction.response.send_message(
                            "No Minecraft account is currently linked to your Discord.",
                            ephemeral=True
                        )

    def run(self):
        self.client.run(os.getenv('DISCORD_BOT_TOKEN'))


if __name__ == "__main__":
    bot = RegistrationBot()
    bot.run()