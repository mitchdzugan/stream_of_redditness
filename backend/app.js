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
    clients[guid] = {
        ws: ws,
        rooms: {}
    };
    console.log("Connection from: " + guid);
    ws.on('message', function (inputStr) {
        var input = JSON.parse(inputStr);
        if (input.inst == 'join') {
            clients[guid].rooms[input.thread] = true;

            var room = rooms[input.thread];

            if (!room) {
                room = {};
                rooms[input.thread] = room;
            }

            clients[guid].ws.send(JSON.stringify({type: "thread-members-all",
                                                  thread: input.thread,
                                                  members: Object.keys(room)}));

            for (var member in Object.keys(room)) {
                clients[member].ws.send(JSON.stringify({type: "thread-members-join",
                                                        thread: input.thread,
                                                        member: guid}));
            }

            room[guid] = true;

        } else if (input.inst == 'leave') {
            if (rooms[input.thread]) {
                delete clients[guid].rooms[input.thread];
                delete rooms[input.thread][guid];
                for (var member in Object.keys(rooms[input.thread])) {
                    clients[member].ws.send(JSON.stringify({type: "thread-members-leave",
                                                            thread: input.thread,
                                                            member: guid}));
                }
            }
        } else if (input.inst == 'send') {
            clients[input.peerId].ws.send(JSON.stringify(input.message));
        } else if (input.inst == 'top-threads') {
            var sizeOfRoom = function(thread) {return Object.keys(rooms[thread]).length;};
            var topRooms = Object.keys(rooms)
                .sort(function(t1, t2) {return sizeOfRoom(t1) - sizeOfRoom(t2);})
                .slice(0, 20);
            clients[guid].ws.send(JSON.stringify({type: "top-rooms",
                                                  topRooms: topRooms}));
        }
    });
});

server.on('request', app);
server.listen(3000, "localhost", function () { console.log('Listening on ' + server.address().port); });
