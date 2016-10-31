var server = require('http').createServer(),
    express = require('express'),
    cors = require('cors'),
    app = express(),
    Guid = require('guid'),
    WebSocketServer = require('ws').Server,
    wss = new WebSocketServer({ server: server, port: 8000 });

app.use(cors);

var clients = {};

var rooms = {};

wss.on('connection', function (ws) {
    var guid = Guid.raw();
    clients[guid] = ws;
    console.log("Connection from: " + guid);
    ws.on('message', function (inputStr) {
        var input = JSON.parse(inputStr);
        if(input.inst == 'join') {

            var room = rooms[input.thread];

            if (!room) {
                room = [];
                rooms[input.thread] = room;
            }

            clients[guid].send(JSON.stringify({type: "thread-members-on-join",
                                               thread: input.thread,
                                               members: room}));

            for (var member in rooms) {
                clients[member].send(JSON.stringify({type: "thread-new-member",
                                                     thread: input.thread,
                                                     member: guid}));
            }

            room.push(guid);

        } else if(input.inst == 'send') {
            clientMap[input.peerId].send(JSON.stringify(input.message));
        }
    });
});

server.on('request', app);
server.listen(3000, "localhost", function () { console.log('Listening on ' + server.address().port); });
