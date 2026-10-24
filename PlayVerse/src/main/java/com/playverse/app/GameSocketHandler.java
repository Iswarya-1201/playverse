package com.playverse.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;

@Component
public class GameSocketHandler extends TextWebSocketHandler {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Room> rooms = new ConcurrentHashMap<>();
    private final Map<String, String> sessionRooms = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private static final int QUESTION_SECONDS = 20;
    private static final List<Q> BANK = List.of(
        new Q("Which keyword creates an object in Java?", new String[]{"class","new","this","void"},1,"Java"),
        new Q("Which collection stores unique elements?", new String[]{"ArrayList","LinkedList","HashSet","Vector"},2,"Java"),
        new Q("Which HTML tag creates a hyperlink?", new String[]{"<link>","<a>","<href>","<url>"},1,"Web"),
        new Q("Which CSS property changes text colour?", new String[]{"font-style","background","color","text-size"},2,"Web"),
        new Q("What does SQL SELECT do?", new String[]{"Deletes rows","Reads data","Creates users","Stops a server"},1,"Database"),
        new Q("Which data structure follows FIFO?", new String[]{"Stack","Queue","Tree","Graph"},1,"DSA"),
        new Q("What is 12 × 8?", new String[]{"84","92","96","108"},2,"Aptitude"),
        new Q("Which protocol secures web traffic?", new String[]{"FTP","HTTP","HTTPS","SMTP"},2,"Networking"),
        new Q("Which Java type stores true or false?", new String[]{"int","char","boolean","double"},2,"Java"),
        new Q("Which method is the usual Java program entry point?", new String[]{"start()","main()","runApp()","init()"},1,"Java"),
        new Q("Which symbol begins a CSS class selector?", new String[]{"#",".","@","*"},1,"Web"),
        new Q("Binary search works best on what?", new String[]{"Unsorted data","Sorted data","Images only","Empty data"},1,"DSA"),
        new Q("Which SQL command adds a new row?", new String[]{"INSERT","ALTER","DROP","SELECT"},0,"Database"),
        new Q("What is the result of 2⁵?", new String[]{"10","16","25","32"},3,"Aptitude"),
        new Q("Which one is not a Java primitive type?", new String[]{"int","boolean","String","char"},2,"Java"),
        new Q("What does API stand for?", new String[]{"App Program Internet","Application Programming Interface","Applied Process Input","Automated Program Install"},1,"Technology")
    );
    private final SecureRandom random = new SecureRandom();

    @Override protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        JsonNode n;
        try { n = mapper.readTree(message.getPayload()); } catch (Exception e) { sendError(session,"Invalid message format."); return; }
        String action = n.path("action").asText("");
        switch (action) {
            case "create" -> createRoom(session, n.path("name").asText("Player"));
            case "join" -> joinRoom(session, n.path("room").asText("").toUpperCase(Locale.ROOT), n.path("name").asText("Player"));
            case "start" -> startGame(session);
            case "answer" -> answer(session, n.path("index").asInt(-1));
            case "leave" -> leaveRoom(session);
            default -> sendError(session,"Unknown action.");
        }
    }
    private void createRoom(WebSocketSession s, String name) throws IOException {
        leaveRoom(s); String code = roomCode(); Room r = new Room(code); rooms.put(code,r); r.players.put(s.getId(), new Player(cleanName(name),s)); sessionRooms.put(s.getId(),code); broadcast(r,"roomCreated",null); broadcast(r,"lobby",null);
    }
    private void joinRoom(WebSocketSession s, String code, String name) throws IOException {
        Room r = rooms.get(code); if (r == null) { sendError(s,"Room not found. Check the code and try again."); return; }
        if (r.started) { sendError(s,"This quiz has already started."); return; }
        if (r.players.size() >= 8) { sendError(s,"This room is full (8 players maximum)."); return; }
        leaveRoom(s); r.players.put(s.getId(),new Player(cleanName(name),s)); sessionRooms.put(s.getId(),code); broadcast(r,"lobby",null);
    }
    private void startGame(WebSocketSession s) throws IOException {
        Room r = roomFor(s); if (r == null) { sendError(s,"Create or join a room first."); return; }
        if (!r.players.containsKey(s.getId())) return;
        if (r.started) return;
        if (r.players.size() < 1) { sendError(s,"At least one player is needed."); return; }
        r.started=true; r.index=0; r.questions=new ArrayList<>(BANK); Collections.shuffle(r.questions,random); if(r.questions.size()>10) r.questions=r.questions.subList(0,10); r.players.values().forEach(p->{p.score=0;p.answered=false;}); beginQuestion(r);
    }
    private void beginQuestion(Room r) {
        if (r.index >= r.questions.size()) { r.started=false; broadcast(r,"finished",null); return; }
        r.players.values().forEach(p->p.answered=false); r.endsAt=System.currentTimeMillis()+QUESTION_SECONDS*1000L; broadcast(r,"question",null);
        scheduler.schedule(() -> { if(r.started && System.currentTimeMillis()>=r.endsAt) { r.index++; beginQuestion(r); } },QUESTION_SECONDS,TimeUnit.SECONDS);
    }
    private void answer(WebSocketSession s, int idx) throws IOException {
        Room r=roomFor(s); if(r==null || !r.started || r.index>=r.questions.size()) return; Player p=r.players.get(s.getId()); if(p==null || p.answered) return;
        if(System.currentTimeMillis()>r.endsAt) return;
        p.answered=true; Q q=r.questions.get(r.index); if(idx==q.correct) p.score+=Math.max(10,(int)Math.ceil((r.endsAt-System.currentTimeMillis())/1000.0)*5);
        ObjectNode msg=base("answerAck"); msg.put("score",p.score); msg.put("correct",idx==q.correct); send(s,msg); broadcast(r,"scores",null);
        boolean all=r.players.values().stream().allMatch(x->x.answered); if(all){r.index++;beginQuestion(r);}
    }
    private void broadcast(Room r,String type,String ignored) throws IOException {
        ObjectNode m=base(type); m.put("room",r.code); m.put("started",r.started); m.put("index",r.index); m.put("total",r.questions.size()); m.put("endsAt",r.endsAt);
        ArrayNode players=m.putArray("players"); r.players.values().stream().sorted(Comparator.comparingInt((Player p)->p.score).reversed()).forEach(p->{ObjectNode x=players.addObject();x.put("name",p.name);x.put("score",p.score);x.put("answered",p.answered);});
        if(type.equals("question") && r.index<r.questions.size()){Q q=r.questions.get(r.index);m.put("prompt",q.prompt);m.put("topic",q.topic);ArrayNode opts=m.putArray("options");for(String o:q.options)opts.add(o);}
        for(Player p:r.players.values()) send(p.session,m);
    }
    private ObjectNode base(String type){ObjectNode n=mapper.createObjectNode();n.put("type",type);return n;}
    private void sendError(WebSocketSession s,String msg)throws IOException{ObjectNode n=base("error");n.put("message",msg);send(s,n);}
    private void send(WebSocketSession s,JsonNode n)throws IOException{if(s!=null&&s.isOpen())s.sendMessage(new TextMessage(mapper.writeValueAsString(n)));}
    private Room roomFor(WebSocketSession s){String code=sessionRooms.get(s.getId());return code==null?null:rooms.get(code);}
    private void leaveRoom(WebSocketSession s)throws IOException{String code=sessionRooms.remove(s.getId());if(code==null)return;Room r=rooms.get(code);if(r!=null){r.players.remove(s.getId());if(r.players.isEmpty())rooms.remove(code);else broadcast(r,"lobby",null);}}
    @Override public void afterConnectionClosed(WebSocketSession s, CloseStatus status)throws Exception{leaveRoom(s);}
    private String roomCode(){String chars="ABCDEFGHJKLMNPQRSTUVWXYZ23456789";StringBuilder b=new StringBuilder();do{b.setLength(0);for(int i=0;i<5;i++)b.append(chars.charAt(random.nextInt(chars.length())));}while(rooms.containsKey(b.toString()));return b.toString();}
    private String cleanName(String s){s=s==null?"":s.trim().replaceAll("[^\\p{L}\\p{N} _-]","");if(s.isBlank())s="Player";return s.substring(0,Math.min(18,s.length()));}
    private static class Room {final String code;final Map<String,Player> players=new ConcurrentHashMap<>();volatile boolean started;volatile int index;volatile long endsAt;List<Q> questions=new ArrayList<>();Room(String c){code=c;}}
    private static class Player {final String name;final WebSocketSession session;volatile int score;volatile boolean answered;Player(String n,WebSocketSession s){name=n;session=s;}}
    private record Q(String prompt,String[] options,int correct,String topic){}
}
