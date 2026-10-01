package com.sahahub.community.web;

import java.time.LocalDateTime;

import org.springframework.format.annotation.DateTimeFormat;

import com.sahahub.community.app.ListingService;
import com.sahahub.community.domain.Listing;

/** İlan formu. Ayrıntılı kurallar ListingService'tedir; burada yalnızca ekrandaki değerler tutulur. */
public class ListingForm {

	private Listing.Kind kind = Listing.Kind.PLAYERS_WANTED;
	private Long teamId;
	private Long reservationId;
	private String city;
	private String district;

	@DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
	private LocalDateTime playAt;

	private Integer playersNeeded = 2;
	private Listing.Level level = Listing.Level.CASUAL;
	private String note;

	public ListingService.CreateCommand toCommand() {
		boolean players = kind == Listing.Kind.PLAYERS_WANTED;
		return new ListingService.CreateCommand(kind, teamId, reservationId, city, district,
				reservationId == null ? playAt : null, players ? playersNeeded : null, level, note);
	}

	public Listing.Kind getKind() {
		return kind;
	}

	public void setKind(Listing.Kind kind) {
		this.kind = kind;
	}

	public Long getTeamId() {
		return teamId;
	}

	public void setTeamId(Long teamId) {
		this.teamId = teamId;
	}

	public Long getReservationId() {
		return reservationId;
	}

	public void setReservationId(Long reservationId) {
		this.reservationId = reservationId;
	}

	public String getCity() {
		return city;
	}

	public void setCity(String city) {
		this.city = city;
	}

	public String getDistrict() {
		return district;
	}

	public void setDistrict(String district) {
		this.district = district;
	}

	public LocalDateTime getPlayAt() {
		return playAt;
	}

	public void setPlayAt(LocalDateTime playAt) {
		this.playAt = playAt;
	}

	public Integer getPlayersNeeded() {
		return playersNeeded;
	}

	public void setPlayersNeeded(Integer playersNeeded) {
		this.playersNeeded = playersNeeded;
	}

	public Listing.Level getLevel() {
		return level;
	}

	public void setLevel(Listing.Level level) {
		this.level = level;
	}

	public String getNote() {
		return note;
	}

	public void setNote(String note) {
		this.note = note;
	}

}
